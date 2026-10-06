package com.golf.course.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.golf.course.common.BizException;
import com.golf.course.common.Dict;
import com.golf.course.dto.HandicapHistoryItem;
import com.golf.course.dto.HandicapSummary;
import com.golf.course.dto.MissingParamsView;
import com.golf.course.dto.PlayerHandicapView;
import com.golf.course.dto.RecalcRequest;
import com.golf.course.dto.RecalcResult;
import com.golf.course.entity.HandicapHistory;
import com.golf.course.entity.HandicapHistoryRound;
import com.golf.course.entity.Player;
import com.golf.course.entity.Scorecard;
import com.golf.course.entity.Tournament;
import com.golf.course.repository.HandicapHistoryRepository;
import com.golf.course.repository.HandicapHistoryRoundRepository;
import com.golf.course.repository.PlayerRepository;
import com.golf.course.repository.ScorecardRepository;
import com.golf.course.repository.TournamentRepository;

/**
 * 差点指数查询与定向重算。
 *
 * <p>查询全部基于 handicap_history / handicap_history_round 的留痕，
 * 历史指数按当时规则可追溯；定向重算用于「补齐球场难度参数后只重算受影响球员」，
 * 逐球员独立事务，失败逐个返回不中断。</p>
 */
@Service
public class HandicapService {

    private final PlayerRepository playerRepository;
    private final HandicapHistoryRepository historyRepository;
    private final HandicapHistoryRoundRepository roundRepository;
    private final TournamentRepository tournamentRepository;
    private final ScorecardRepository scorecardRepository;
    private final HandicapRecalcService recalcService;

    public HandicapService(PlayerRepository playerRepository,
                           HandicapHistoryRepository historyRepository,
                           HandicapHistoryRoundRepository roundRepository,
                           TournamentRepository tournamentRepository,
                           ScorecardRepository scorecardRepository,
                           HandicapRecalcService recalcService) {
        this.playerRepository = playerRepository;
        this.historyRepository = historyRepository;
        this.roundRepository = roundRepository;
        this.tournamentRepository = tournamentRepository;
        this.scorecardRepository = scorecardRepository;
        this.recalcService = recalcService;
    }

    /** 球员列表页汇总：每个球员最新一次计算（一条 SQL 取回，不逐球员查库）。 */
    @Transactional(readOnly = true)
    public List<HandicapSummary> summary() {
        return historyRepository.findLatestPerPlayer().stream()
                .map(h -> new HandicapSummary(h.getPlayer().getId(), h.getStatus(),
                        h.getIndexValue(), h.getChangeAmount(), h.getDropLimited(),
                        h.getAsOfDate(), h.getCreatedAt()))
                .toList();
    }

    /** 球员差点详情：当前指数、最近变化、采用与排除的轮次、对应规则。 */
    @Transactional(readOnly = true)
    public PlayerHandicapView playerView(Long playerId) {
        Player player = requirePlayer(playerId);
        HandicapHistory latest = historyRepository.findTopByPlayerIdOrderByIdDesc(playerId).orElse(null);
        if (latest == null) {
            return new PlayerHandicapView(player.getId(), player.getPlayerNo(), player.getName(),
                    player.getHandicap(), "未计算", null, null, null, null, null,
                    null, null, null, null, null, null, null, List.of());
        }
        List<PlayerHandicapView.RoundItem> rounds = roundRepository
                .findByHistoryIdOrderByIdAsc(latest.getId()).stream()
                .map(r -> new PlayerHandicapView.RoundItem(
                        r.getTournament().getId(), r.getTournament().getName(), r.getRoundNo(),
                        r.getPlayDate(), r.getGrossScore(), r.getCourseRating(), r.getSlopeRating(),
                        r.getDifferential(), r.getCapped(), r.getUsed(), r.getExclusionReason()))
                .toList();
        return new PlayerHandicapView(player.getId(), player.getPlayerNo(), player.getName(),
                player.getHandicap(), latest.getStatus(), latest.getIndexValue(),
                latest.getPreviousIndex(), latest.getChangeAmount(), latest.getDropLimited(),
                latest.getMissingParams(), latest.getSampleSize(), latest.getUsedCount(),
                latest.getRule().getRuleName(), latest.getRule().getEffectiveDate(),
                latest.getAsOfDate(), latest.getCreatedAt(), latest.getRemark(), rounds);
    }

    /** 球员历次计算记录：每次计算的规则与结果都可追溯。 */
    @Transactional(readOnly = true)
    public List<HandicapHistoryItem> history(Long playerId) {
        requirePlayer(playerId);
        return historyRepository.findByPlayerIdOrderByIdDesc(playerId).stream()
                .map(h -> new HandicapHistoryItem(h.getId(), h.getAsOfDate(), h.getCreatedAt(),
                        h.getStatus(), h.getIndexValue(), h.getPreviousIndex(), h.getChangeAmount(),
                        h.getDropLimited(), h.getSampleSize(), h.getUsedCount(),
                        h.getRule().getRuleName(), h.getRule().getEffectiveDate(),
                        h.getRun() == null ? null : h.getRun().getId(), h.getRemark()))
                .toList();
    }

    /**
     * 缺球场难度参数的赛事及受影响球员（有已签核轮次但因缺参被排除）。
     * 这是旧数据缺项的明确清单：补齐参数后应对这些球员做定向重算。
     */
    @Transactional(readOnly = true)
    public List<MissingParamsView> missingParams() {
        List<Tournament> missing = tournamentRepository.findMissingDifficulty();
        if (missing.isEmpty()) {
            return List.of();
        }
        List<Long> ids = missing.stream().map(Tournament::getId).toList();
        Map<Long, Map<Long, Player>> byTournament = new LinkedHashMap<>();
        for (Scorecard card : scorecardRepository.findByStatusAndTournamentIdIn(Dict.CARD_SIGNED, ids)) {
            byTournament.computeIfAbsent(card.getTournament().getId(), k -> new LinkedHashMap<>())
                    .putIfAbsent(card.getPlayer().getId(), card.getPlayer());
        }
        List<MissingParamsView> views = new ArrayList<>();
        for (Tournament t : missing) {
            List<MissingParamsView.AffectedPlayer> players = byTournament
                    .getOrDefault(t.getId(), Map.of()).values().stream()
                    .map(p -> new MissingParamsView.AffectedPlayer(p.getId(), p.getPlayerNo(), p.getName()))
                    .toList();
            views.add(new MissingParamsView(t.getId(), t.getTournamentNo(), t.getName(), t.getStatus(),
                    t.getEndDate(), t.getCourseRating(), t.getSlopeRating(), players));
        }
        return views;
    }

    /**
     * 定向重算：只重算指定球员（如补齐难度参数后受影响的球员）。
     * 逐球员独立事务，单个失败不影响其他人，结果逐个返回。
     */
    public List<RecalcResult> recalcTargeted(RecalcRequest req) {
        if (req.playerIds() == null || req.playerIds().isEmpty()) {
            throw new BizException("请先选择要重算的球员");
        }
        LocalDate asOf = req.asOfDate() != null ? req.asOfDate() : LocalDate.now();
        recalcService.ruleFor(asOf); // 无生效规则时直接失败，不逐个球员报错
        List<RecalcResult> results = new ArrayList<>();
        for (Long playerId : req.playerIds().stream().distinct().toList()) {
            try {
                HandicapHistory h = recalcService.recalcOne(playerId, asOf, null);
                results.add(new RecalcResult(playerId, h.getPlayer().getName(), true,
                        h.getStatus(), h.getIndexValue(),
                        Dict.HCP_INSUFFICIENT.equals(h.getStatus()) ? "样本不足，未出指数" : "重算完成"));
            } catch (Exception e) {
                String name = playerRepository.findById(playerId).map(Player::getName).orElse(null);
                results.add(new RecalcResult(playerId, name, false, null, null, e.getMessage()));
            }
        }
        return results;
    }

    private Player requirePlayer(Long playerId) {
        return playerRepository.findById(playerId)
                .orElseThrow(() -> new BizException("球员不存在：" + playerId));
    }
}
