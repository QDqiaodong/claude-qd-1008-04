package com.golf.course.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;

import com.golf.course.common.BizException;
import com.golf.course.common.Dict;
import com.golf.course.dto.RunRequest;
import com.golf.course.entity.HandicapHistory;
import com.golf.course.entity.HandicapRule;
import com.golf.course.entity.HandicapRun;
import com.golf.course.entity.HandicapRunError;
import com.golf.course.entity.Player;
import com.golf.course.entity.Scorecard;
import com.golf.course.entity.Tournament;
import com.golf.course.repository.HandicapRunErrorRepository;
import com.golf.course.repository.HandicapRunRepository;
import com.golf.course.repository.ScorecardRepository;
import com.golf.course.repository.TournamentRepository;

/**
 * 全量差点重算：按赛事日期分批执行，逐球员独立事务，失败明细留痕。
 *
 * <p>执行模型（面向十万级逐洞成绩设计）：
 * <ol>
 *   <li>按赛事结束日升序把赛事切成若干批（batchSize 场 / 批），每批一次 IN 查询
 *       取回该批全部已签核记分卡（join fetch），只扫描一次、不重复扫描；</li>
 *   <li>汇总出受影响球员集合后，逐球员调用
 *       {@link HandicapRecalcService#recalcOne}（走 (player_id, status) 索引），
 *       绝不为每个球员反复全表扫描成绩；</li>
 *   <li>每个球员独立事务：单个失败仅记录 {@code handicap_run_error} 并继续，
 *       批次以 已完成 / 部分失败 收口，失败明细（批次、球员、原因）全部可查。</li>
 * </ol>
 *
 * <p>注意：分批只用于「圈定受影响球员」；每个球员的重算始终基于其基准日之前
 * 全部已完成轮次（不限于批次日期范围），保证结果与单球员重算一致。</p>
 */
@Service
public class HandicapRunService {

    private final HandicapRunRepository runRepository;
    private final HandicapRunErrorRepository runErrorRepository;
    private final TournamentRepository tournamentRepository;
    private final ScorecardRepository scorecardRepository;
    private final HandicapRecalcService recalcService;

    public HandicapRunService(HandicapRunRepository runRepository,
                              HandicapRunErrorRepository runErrorRepository,
                              TournamentRepository tournamentRepository,
                              ScorecardRepository scorecardRepository,
                              HandicapRecalcService recalcService) {
        this.runRepository = runRepository;
        this.runErrorRepository = runErrorRepository;
        this.tournamentRepository = tournamentRepository;
        this.scorecardRepository = scorecardRepository;
        this.recalcService = recalcService;
    }

    public List<HandicapRun> list() {
        return runRepository.findAllByOrderByIdDesc();
    }

    public HandicapRun get(Long id) {
        return runRepository.findById(id)
                .orElseThrow(() -> new BizException("重算批次不存在：" + id));
    }

    public List<HandicapRunError> errors(Long runId) {
        get(runId);
        return runErrorRepository.findByRunIdOrderByIdAsc(runId);
    }

    /**
     * 执行全量重算。本方法刻意不加大事务：批次行的状态推进、每个球员的重算、
     * 失败明细都各自提交——长批次中途出错也不会丢掉已完成的成果。
     */
    public HandicapRun execute(RunRequest req) {
        LocalDate asOf = req.asOfDate() != null ? req.asOfDate() : LocalDate.now();
        HandicapRule rule = recalcService.ruleFor(asOf);
        if (req.fromDate() != null && req.toDate() != null && req.fromDate().isAfter(req.toDate())) {
            throw new BizException("赛事日期范围起点不能晚于终点");
        }
        int batchSize = req.batchSize() == null ? 20 : req.batchSize();
        if (batchSize < 1 || batchSize > 500) {
            throw new BizException("分批大小须在 1 ~ 500 之间，当前：" + batchSize);
        }

        HandicapRun run = new HandicapRun();
        run.setRunNo(newRunNo());
        run.setAsOfDate(asOf);
        run.setRule(rule);
        run.setFromDate(req.fromDate());
        run.setToDate(req.toDate());
        run.setBatchSize(batchSize);
        run.setStatus(Dict.RUN_RUNNING);
        run.setTotalPlayers(0);
        run.setSuccessCount(0);
        run.setInsufficientCount(0);
        run.setFailCount(0);
        run.setTriggeredBy(req.triggeredBy() == null || req.triggeredBy().isBlank()
                ? "管理员" : req.triggeredBy().trim());
        run.setStartedAt(LocalDateTime.now());
        run = runRepository.save(run);

        // 按赛事日期分批：只扫描日期范围内、基准日之前已结束的赛事
        List<Tournament> tournaments = tournamentRepository.findAll().stream()
                .filter(t -> playDate(t) != null)
                .filter(t -> !playDate(t).isAfter(asOf))
                .filter(t -> req.fromDate() == null || !playDate(t).isBefore(req.fromDate()))
                .filter(t -> req.toDate() == null || !playDate(t).isAfter(req.toDate()))
                .sorted(Comparator.comparing(HandicapRunService::playDate).thenComparing(Tournament::getId))
                .toList();

        // 分批扫描已签核卡，圈定受影响球员（每个球员只算一次，与其出现在几批无关）
        Map<Long, Player> affected = new LinkedHashMap<>();
        Map<Long, String> playerBatch = new LinkedHashMap<>();
        for (List<Tournament> batch : chunks(tournaments, batchSize)) {
            String label = batchLabel(batch);
            try {
                List<Long> ids = batch.stream().map(Tournament::getId).toList();
                for (Scorecard card : scorecardRepository.findByStatusAndTournamentIdIn(Dict.CARD_SIGNED, ids)) {
                    Player p = card.getPlayer();
                    affected.putIfAbsent(p.getId(), p);
                    playerBatch.putIfAbsent(p.getId(), label);
                }
            } catch (Exception e) {
                recordError(run, label, null, null, "批次扫描失败：" + e.getMessage());
            }
        }

        int success = 0;
        int insufficient = 0;
        int fail = 0;
        for (Map.Entry<Long, Player> entry : affected.entrySet()) {
            Player player = entry.getValue();
            try {
                HandicapHistory history = recalcService.recalcOne(player.getId(), asOf, run);
                if (Dict.HCP_INSUFFICIENT.equals(history.getStatus())) {
                    insufficient++;
                } else {
                    success++;
                }
            } catch (Exception e) {
                fail++;
                recordError(run, playerBatch.get(player.getId()), player.getId(),
                        player.getName(), e.getMessage());
            }
        }

        run.setTotalPlayers(affected.size());
        run.setSuccessCount(success);
        run.setInsufficientCount(insufficient);
        run.setFailCount(fail);
        run.setStatus(fail > 0 ? Dict.RUN_PARTIAL : Dict.RUN_DONE);
        run.setFinishedAt(LocalDateTime.now());
        return runRepository.save(run);
    }

    private void recordError(HandicapRun run, String batchLabel, Long playerId,
                             String playerName, String message) {
        HandicapRunError error = new HandicapRunError();
        error.setRun(run);
        error.setBatchLabel(batchLabel);
        error.setPlayerId(playerId);
        error.setPlayerName(playerName);
        error.setMessage(message == null ? "未知错误"
                : message.length() > 255 ? message.substring(0, 255) : message);
        error.setCreatedAt(LocalDateTime.now());
        runErrorRepository.save(error);
    }

    private static LocalDate playDate(Tournament t) {
        return t.getEndDate() != null ? t.getEndDate() : t.getStartDate();
    }

    private static List<List<Tournament>> chunks(List<Tournament> tournaments, int size) {
        List<List<Tournament>> chunks = new ArrayList<>();
        for (int i = 0; i < tournaments.size(); i += size) {
            chunks.add(tournaments.subList(i, Math.min(i + size, tournaments.size())));
        }
        return chunks;
    }

    private static String batchLabel(List<Tournament> batch) {
        return playDate(batch.get(0)) + " ~ " + playDate(batch.get(batch.size() - 1))
                + " · " + batch.size() + "场";
    }

    private static String newRunNo() {
        return "HR-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + (100 + ThreadLocalRandom.current().nextInt(900));
    }
}
