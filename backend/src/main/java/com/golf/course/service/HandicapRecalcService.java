package com.golf.course.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.golf.course.common.BizException;
import com.golf.course.common.Dict;
import com.golf.course.dto.Snapshot;
import com.golf.course.entity.HandicapHistory;
import com.golf.course.entity.HandicapHistoryRound;
import com.golf.course.entity.HandicapRule;
import com.golf.course.entity.HandicapRun;
import com.golf.course.entity.Player;
import com.golf.course.entity.Scorecard;
import com.golf.course.entity.Tournament;
import com.golf.course.repository.HandicapHistoryRepository;
import com.golf.course.repository.HandicapHistoryRoundRepository;
import com.golf.course.repository.HandicapRuleRepository;
import com.golf.course.repository.PlayerRepository;
import com.golf.course.repository.ScorecardRepository;

/**
 * 差点指数计算核心：根据球员最近 N 轮已完成（已签核）比赛的逐洞杆数、
 * 球洞标准杆与球场难度参数，按生效规则算出差点指数，并完整留痕。
 *
 * <p>口径（WHS 风格）：
 * <ol>
 *   <li>轮次来源：已签核记分卡的固化快照；比赛日期取赛事结束日（无则开始日），
 *       只取计算基准日（含）之前已结束的轮次，按日期倒序取最近 {@code roundsWindow} 轮；</li>
 *   <li>轮差值 = (113 / slopeRating) × (总杆 − courseRating)，保留一位小数；
 *       赛事缺球场难度参数的轮次被明确排除并记缺项；差值超规则上限按上限截断（仍参与采用）；</li>
 *   <li>可计轮次 &lt; 规则最少轮次 → 状态「样本不足」，不出指数；
 *       否则按映射表取最低 K 个差值求平均，保留一位小数，并夹在 0 ~ 54；</li>
 *   <li>单次下调幅度超过规则 maxDrop 时按 maxDrop 限幅，防止录入错误让指数突然失真。</li>
 * </ol>
 *
 * <p>每次计算落一行 {@link HandicapHistory}（含规则快照）+ 窗口内逐轮
 * {@link HandicapHistoryRound}（采用 / 排除及原因），并同步 player.handicap_index；
 * 历史行永不修改，历史指数因此始终按当时规则可追溯。</p>
 *
 * <p>性能：单球员重算走 (player_id, status) 索引查询，每个球员独立事务
 * （{@code REQUIRES_NEW}）——批量重算中单个球员失败不会回滚其他球员的成果。</p>
 */
@Service
public class HandicapRecalcService {

    private final PlayerRepository playerRepository;
    private final ScorecardRepository scorecardRepository;
    private final HandicapRuleRepository ruleRepository;
    private final HandicapHistoryRepository historyRepository;
    private final HandicapHistoryRoundRepository roundRepository;
    private final ObjectMapper objectMapper;

    public HandicapRecalcService(PlayerRepository playerRepository,
                                 ScorecardRepository scorecardRepository,
                                 HandicapRuleRepository ruleRepository,
                                 HandicapHistoryRepository historyRepository,
                                 HandicapHistoryRoundRepository roundRepository,
                                 ObjectMapper objectMapper) {
        this.playerRepository = playerRepository;
        this.scorecardRepository = scorecardRepository;
        this.ruleRepository = ruleRepository;
        this.historyRepository = historyRepository;
        this.roundRepository = roundRepository;
        this.objectMapper = objectMapper;
    }

    /** 计算基准日（含）之前已生效的最新规则；没有则明确报错（批量重算中记为失败明细）。 */
    @Transactional(readOnly = true)
    public HandicapRule ruleFor(LocalDate asOfDate) {
        return ruleRepository.findTopByEffectiveDateLessThanEqualOrderByEffectiveDateDesc(asOfDate)
                .orElseThrow(() -> new BizException("计算基准日 " + asOfDate
                        + " 没有任何已生效的差点计算规则，请先在差点中心维护规则"));
    }

    /**
     * 重算单个球员的差点指数。独立事务：批量重算中逐球员调用，
     * 单个球员失败只影响自己，成果与失败明细都能各自落库。
     *
     * @param playerId 球员
     * @param asOfDate 计算基准日（只取该日之前已结束的轮次）
     * @param run      所属批次；定向 / 单球员重算传 null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public HandicapHistory recalcOne(Long playerId, LocalDate asOfDate, HandicapRun run) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new BizException("球员不存在：" + playerId));
        HandicapRule rule = ruleFor(asOfDate);
        TreeMap<Integer, Integer> bestTable = HandicapRuleService.parseBestCountTable(rule.getBestCountTable());

        // 已签核 = 已完成轮次；索引化单球员查询，不扫全表
        List<Scorecard> signed = scorecardRepository.findByPlayerIdAndStatusWithTournament(
                playerId, Dict.CARD_SIGNED);

        // 只取基准日（含）之前已结束的轮次，按比赛日期倒序进入最近 N 轮窗口
        List<Scorecard> eligible = signed.stream()
                .filter(c -> playDateOf(c) != null && !playDateOf(c).isAfter(asOfDate))
                .sorted(Comparator.comparing(this::playDateOf).reversed()
                        .thenComparing(Comparator.comparing(Scorecard::getId).reversed()))
                .toList();
        int beyondWindow = Math.max(0, eligible.size() - rule.getRoundsWindow());
        List<Scorecard> window = eligible.stream().limit(rule.getRoundsWindow()).toList();

        // 逐轮算差值；缺参 / 快照损坏的轮次明确排除并记原因
        List<RoundCalc> calcs = new ArrayList<>();
        boolean missingParams = false;
        int cappedCount = 0;
        for (Scorecard card : window) {
            RoundCalc calc = new RoundCalc(card);
            Tournament tournament = card.getTournament();
            calc.courseRating = tournament.getCourseRating();
            calc.slopeRating = tournament.getSlopeRating();
            Snapshot snapshot = readSnapshot(card);
            if (snapshot == null) {
                calc.exclusion = "记分卡快照无法解析，无法还原逐洞杆数";
            } else {
                calc.gross = grossOf(snapshot);
                if (calc.courseRating == null || calc.slopeRating == null) {
                    calc.exclusion = "缺球场难度参数（赛事「" + tournament.getName()
                            + "」未维护 courseRating/slopeRating）";
                    missingParams = true;
                } else {
                    BigDecimal raw = differential(calc.gross, calc.courseRating, calc.slopeRating);
                    if (raw.compareTo(rule.getMaxDifferential()) > 0) {
                        calc.differential = rule.getMaxDifferential();
                        calc.capped = true;
                        cappedCount++;
                    } else {
                        calc.differential = raw;
                    }
                }
            }
            calcs.add(calc);
        }

        List<RoundCalc> computable = calcs.stream().filter(c -> c.differential != null).toList();
        int sampleSize = computable.size();

        String status;
        BigDecimal indexValue = null;
        int usedCount = 0;
        List<String> remarks = new ArrayList<>();

        if (sampleSize < rule.getMinRounds()) {
            status = Dict.HCP_INSUFFICIENT;
            remarks.add("可计轮次 " + sampleSize + " < 规则最少 " + rule.getMinRounds() + " 轮，样本不足");
        } else {
            status = Dict.HCP_CALCULATED;
            int bestK = HandicapRuleService.bestCountFor(bestTable, sampleSize);
            List<RoundCalc> byDiff = computable.stream()
                    .sorted(Comparator.comparing(c -> c.differential))
                    .toList();
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = 0; i < byDiff.size(); i++) {
                RoundCalc c = byDiff.get(i);
                if (i < bestK) {
                    c.used = true;
                    sum = sum.add(c.differential);
                } else {
                    c.exclusion = "差值未进入最低 " + bestK + " 个（按样本 " + sampleSize
                            + " 轮应取 " + bestK + " 个）";
                }
            }
            usedCount = bestK;
            indexValue = sum.divide(BigDecimal.valueOf(bestK), 1, RoundingMode.HALF_UP);
            if (indexValue.compareTo(Dict.HCP_INDEX_MIN) < 0) {
                indexValue = Dict.HCP_INDEX_MIN.setScale(1, RoundingMode.HALF_UP);
            }
            if (indexValue.compareTo(Dict.HCP_INDEX_MAX) > 0) {
                indexValue = Dict.HCP_INDEX_MAX.setScale(1, RoundingMode.HALF_UP);
            }
        }

        // 单次下调幅度限制：相对上一次「已计算」指数，单次最多下调 maxDrop
        BigDecimal previousIndex = historyRepository
                .findTopByPlayerIdAndStatusOrderByIdDesc(playerId, Dict.HCP_CALCULATED)
                .map(HandicapHistory::getIndexValue).orElse(null);
        BigDecimal changeAmount = null;
        boolean dropLimited = false;
        if (Dict.HCP_CALCULATED.equals(status) && previousIndex != null) {
            changeAmount = indexValue.subtract(previousIndex).setScale(1, RoundingMode.HALF_UP);
            if (changeAmount.signum() < 0 && changeAmount.abs().compareTo(rule.getMaxDrop()) > 0) {
                indexValue = previousIndex.subtract(rule.getMaxDrop()).setScale(1, RoundingMode.HALF_UP);
                changeAmount = indexValue.subtract(previousIndex).setScale(1, RoundingMode.HALF_UP);
                dropLimited = true;
                remarks.add("单次下调幅度超规则上限 " + rule.getMaxDrop() + "，已按上限下调（防录入错误致指数失真）");
            }
        }

        if (cappedCount > 0) {
            remarks.add(cappedCount + " 轮差值超上限 " + rule.getMaxDifferential() + "，已按上限截断");
        }
        if (missingParams) {
            remarks.add("存在缺球场难度参数的轮次，已排除；补齐参数后可定向重算");
        }
        if (beyondWindow > 0) {
            remarks.add("另有 " + beyondWindow + " 轮更早成绩未进入最近 " + rule.getRoundsWindow() + " 轮窗口");
        }

        LocalDateTime now = LocalDateTime.now();
        HandicapHistory history = new HandicapHistory();
        history.setPlayer(player);
        history.setRun(run);
        history.setRule(rule);
        history.setRuleJson(ruleSnapshot(rule));
        history.setAsOfDate(asOfDate);
        history.setStatus(status);
        history.setSampleSize(sampleSize);
        history.setUsedCount(usedCount);
        history.setIndexValue(indexValue);
        history.setPreviousIndex(previousIndex);
        history.setChangeAmount(changeAmount);
        history.setDropLimited(dropLimited);
        history.setMissingParams(missingParams);
        history.setRemark(remarks.isEmpty() ? null : String.join("；", remarks));
        history.setCreatedAt(now);
        historyRepository.save(history);

        List<HandicapHistoryRound> rows = new ArrayList<>();
        for (RoundCalc calc : calcs) {
            HandicapHistoryRound row = new HandicapHistoryRound();
            row.setHistory(history);
            row.setTournament(calc.card.getTournament());
            row.setRoundNo(calc.card.getRoundNo());
            row.setPlayDate(playDateOf(calc.card));
            row.setGrossScore(calc.gross);
            row.setCourseRating(calc.courseRating);
            row.setSlopeRating(calc.slopeRating);
            row.setDifferential(calc.differential);
            row.setCapped(calc.capped);
            row.setUsed(calc.used);
            row.setExclusionReason(calc.exclusion);
            rows.add(row);
        }
        roundRepository.saveAll(rows);

        // 同步球员当前指数（样本不足时置空——当前指数如实反映最近一次计算，历史不丢）
        player.setHandicapIndex(indexValue);
        player.setHandicapIndexAt(now);
        playerRepository.save(player);
        return history;
    }

    /** 轮差值 = (113 / slope) × (总杆 − courseRating)，保留一位小数（四舍五入）。 */
    private BigDecimal differential(int gross, BigDecimal courseRating, int slope) {
        return Dict.STANDARD_SLOPE
                .multiply(BigDecimal.valueOf(gross).subtract(courseRating))
                .divide(BigDecimal.valueOf(slope), 1, RoundingMode.HALF_UP);
    }

    /** 比赛日期：取赛事结束日，无结束日则取开始日。 */
    private LocalDate playDateOf(Scorecard card) {
        Tournament tournament = card.getTournament();
        if (tournament == null) {
            return null;
        }
        return tournament.getEndDate() != null ? tournament.getEndDate() : tournament.getStartDate();
    }

    private Snapshot readSnapshot(Scorecard card) {
        if (card.getSnapshotJson() == null || card.getSnapshotJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(card.getSnapshotJson(), Snapshot.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return null;
        }
    }

    /** 该轮总杆：优先快照总杆，缺失时按逐洞杆数求和。 */
    private int grossOf(Snapshot snapshot) {
        if (snapshot.totalStrokes() != null) {
            return snapshot.totalStrokes();
        }
        return snapshot.holes() == null ? 0 : snapshot.holes().stream()
                .filter(h -> h.strokes() != null).mapToInt(Snapshot.Hole::strokes).sum();
    }

    /** 规则参数快照：随历史行固化，规则日后被替代时本次口径仍可还原。 */
    private String ruleSnapshot(HandicapRule rule) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ruleName", rule.getRuleName());
        map.put("effectiveDate", rule.getEffectiveDate().toString());
        map.put("roundsWindow", rule.getRoundsWindow());
        map.put("minRounds", rule.getMinRounds());
        map.put("bestCountTable", rule.getBestCountTable());
        map.put("maxDifferential", rule.getMaxDifferential());
        map.put("maxDrop", rule.getMaxDrop());
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new BizException("规则快照序列化失败：" + e.getMessage());
        }
    }

    /** 单轮计算的中间态。 */
    private static final class RoundCalc {
        private final Scorecard card;
        private Integer gross;
        private BigDecimal courseRating;
        private Integer slopeRating;
        private BigDecimal differential;
        private boolean capped;
        private boolean used;
        private String exclusion;

        private RoundCalc(Scorecard card) {
            this.card = card;
        }
    }
}
