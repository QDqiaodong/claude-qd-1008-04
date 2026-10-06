package com.golf.course.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.golf.course.common.BizException;
import com.golf.course.common.ConflictException;
import com.golf.course.common.Dict;
import com.golf.course.dto.LeaderboardView;
import com.golf.course.entity.Hole;
import com.golf.course.entity.LeaderboardSnapshot;
import com.golf.course.entity.Player;
import com.golf.course.entity.Tournament;
import com.golf.course.entity.TournamentEntry;
import com.golf.course.repository.HoleRepository;
import com.golf.course.repository.LeaderboardSnapshotRepository;
import com.golf.course.repository.PlayerRepository;
import com.golf.course.repository.ScoreRepository;
import com.golf.course.repository.TournamentEntryRepository;
import com.golf.course.repository.TournamentRepository;

/**
 * 赛事排行榜快照业务：收口前后的数据边界、失败回滚与并发覆盖的统一落点。
 *
 * <p>三条不可绕过的规则：</p>
 * <ol>
 *   <li><b>边界</b>——报名中 / 进行中只提供按当前已录入成绩实时计算的「临时结果」并明确标注，
 *       绝不当作正式成绩；推进到「已结束」的同一事务内，基于收口前确认的全部参赛成绩
 *       生成唯一「正式快照」冻结为 JSON。之后任何查询只返回冻结体，补录 / 修改 / 删除成绩
 *       都不再改变名次。</li>
 *   <li><b>收口校验与回滚</b>——缺成绩、成绩属于不存在或非参赛球员、洞号不属于本场、
 *       轮次越界、洞杆数不合法、同球员同轮同洞重复成绩，任一不满足都不允许收口，
 *       原因精确到「赛事 / 球员 / 洞号」逐条返回。赛事状态事务回滚为「进行中」，
 *       不写正式快照行（不留半份正式结果）；失败原因以独立事务留痕，临时排名照常查询。</li>
 *   <li><b>并发</b>——收口与赛后补录都先取赛事行悲观写锁串行化，再叠加赛事 / 快照的
 *       {@code @Version} 乐观锁；过期操作统一 409 提示「数据已变化」并要求重新读取当前状态。
 *       一场赛事至多一份正式快照（{@code uk_snapshot_tournament} 兜底）。</li>
 * </ol>
 */
@Service
public class LeaderboardSnapshotService {

    private final TournamentRepository tournamentRepository;
    private final TournamentEntryRepository entryRepository;
    private final ScoreRepository scoreRepository;
    private final PlayerRepository playerRepository;
    private final HoleRepository holeRepository;
    private final LeaderboardSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public LeaderboardSnapshotService(TournamentRepository tournamentRepository,
                                      TournamentEntryRepository entryRepository,
                                      ScoreRepository scoreRepository,
                                      PlayerRepository playerRepository,
                                      HoleRepository holeRepository,
                                      LeaderboardSnapshotRepository snapshotRepository,
                                      ObjectMapper objectMapper,
                                      PlatformTransactionManager transactionManager) {
        this.tournamentRepository = tournamentRepository;
        this.entryRepository = entryRepository;
        this.scoreRepository = scoreRepository;
        this.playerRepository = playerRepository;
        this.holeRepository = holeRepository;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 收口校验失败：与普通业务校验一样返回 400，但消息固定带「赛事 / 球员 / 洞号」维度，
     * 由收口编排单独捕获后把全部原因写入「生成失败」快照留痕。
     */
    public static class CloseValidationException extends BizException {
        private final List<String> reasons;

        public CloseValidationException(List<String> reasons) {
            super("赛事收口排行榜生成失败：" + String.join("；", reasons));
            this.reasons = List.copyOf(reasons);
        }

        public List<String> reasons() {
            return reasons;
        }
    }

    // ================================================================
    // 查询：临时结果 / 正式快照 / 失败原因 / 历史兼容
    // ================================================================

    /**
     * 读取赛事排行榜。
     * <ul>
     *   <li>存在「正式快照」：原样返回冻结 JSON，绝不回查成绩表；</li>
     *   <li>存在「生成失败」留痕：返回失败原因 + 此刻的临时排名，赛事仍是「进行中」；</li>
     *   <li>报名中 / 进行中：实时计算「临时结果」（页面明确标注）；</li>
     *   <li>修复前已结束、没有正式快照的旧赛事：只读「历史口径」实时排名并标记兼容，
     *       旧赛事、旧成绩不会因为本次修复而消失。</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public LeaderboardView getView(Long tournamentId) {
        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BizException("赛事不存在：" + tournamentId));
        LeaderboardSnapshot snapshot = snapshotRepository.findByTournamentId(tournamentId).orElse(null);

        if (snapshot != null && LeaderboardSnapshot.TYPE_OFFICIAL.equals(snapshot.getSnapshotType())) {
            LeaderboardView frozen = readPayload(snapshot.getOfficialPayloadJson(), tournamentId);
            // 冻结体以落库内容为准；赛事状态字段以库里当前值回显（理论上永远是「已结束」）。
            return withRuntimeState(frozen, tournament, snapshot);
        }

        Computation data = compute(tournament);
        if (snapshot != null && LeaderboardSnapshot.TYPE_FAILED.equals(snapshot.getSnapshotType())) {
            List<String> reasons = readReasons(snapshot.getFailureReasons());
            // 收口失败后赛事仍是「进行中」：展示的是当前临时排名（可继续查询），失败原因单列。
            return assemble(LeaderboardView.KIND_FAILED, true, false, false, tournament,
                    snapshot.getVersion(), snapshot.getGeneratedAt(), reasons, data);
        }
        if ("已结束".equals(tournament.getStatus())) {
            // 历史兼容：修复前结束的赛事没有正式快照，用旧成绩按同一算法给只读历史口径，
            // 页面必须明确它不是本次收口冻结的正式快照。
            return assemble(LeaderboardView.KIND_LEGACY, false, false, true, tournament,
                    null, null, List.of(), data);
        }
        if (snapshot != null) {
            // 防御：出现无法识别的快照类型时不猜口径，明确报内部数据异常，不返回可能误导的排名。
            throw new BizException("赛事 " + tournamentId + " 的排行榜快照类型无法识别："
                    + snapshot.getSnapshotType());
        }
        return assemble(LeaderboardView.KIND_PROVISIONAL, true, false, false, tournament,
                null, null, List.of(), data);
    }

    // ================================================================
    // 收口：推进「已结束」+ 冻结唯一正式快照（原子、可回滚、并发安全）
    // ================================================================

    /**
     * 把「进行中」赛事收口为「已结束」并生成唯一正式排行榜快照。
     *
     * <p>编排（外层不加事务）：</p>
     * <ol>
     *   <li>独立事务内加赛事行悲观写锁 → 做收口校验 → 通过则冻结快照、翻状态，一次提交；
     *       不通过则整体回滚（赛事仍是「进行中」，不留半份正式结果）。</li>
     *   <li>校验失败被捕获后，用第二个独立事务把逐条原因写入 / 覆盖「生成失败」快照行，
     *       原临时排名仍可查询。</li>
     * </ol>
     *
     * @param expectedVersion 页面加载时看到的赛事版本号；非空且与当前不一致直接 409
     */
    public LeaderboardView finalizeOfficial(Long tournamentId, Long expectedVersion) {
        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BizException("赛事不存在：" + tournamentId));
        if (expectedVersion != null && !expectedVersion.equals(tournament.getVersion())) {
            throw new ConflictException("赛事数据已变化：你打开的是 v" + expectedVersion
                    + "，当前已是 v" + tournament.getVersion()
                    + "。本次收口未生效，请重新读取当前排行榜与赛事状态后再操作");
        }

        try {
            return transactionTemplate.execute(status -> doFinalize(tournamentId));
        } catch (CloseValidationException e) {
            // 收口事务已随异常回滚（状态仍「进行中」，无正式行）；失败原因独立事务留痕。
            recordFailure(tournamentId, e.reasons());
            throw e;
        }
    }

    /** 在「赛事行悲观锁 + 赛事乐观锁」保护下完成校验、冻结与状态推进。 */
    private LeaderboardView doFinalize(Long tournamentId) {
        Tournament locked = tournamentRepository.findWithLockById(tournamentId)
                .orElseThrow(() -> new BizException("赛事不存在：" + tournamentId));
        if ("已结束".equals(locked.getStatus())) {
            LeaderboardSnapshot existing = snapshotRepository.findByTournamentId(tournamentId).orElse(null);
            if (existing != null && LeaderboardSnapshot.TYPE_OFFICIAL.equals(existing.getSnapshotType())) {
                throw new ConflictException("赛事「" + locked.getName()
                        + "」已有唯一正式排行榜快照（v" + existing.getVersion()
                        + "），收口结果不可重复生成或覆盖；请重新读取当前状态");
            }
            throw new ConflictException("赛事「" + locked.getName()
                    + "」已经结束，收口操作过期，请重新读取当前状态后再操作");
        }
        if (!"进行中".equals(locked.getStatus())) {
            throw new BizException("只有「进行中」的赛事可以收口生成正式排行榜快照，当前状态："
                    + locked.getStatus());
        }

        Computation data = compute(locked);
        if (!data.violations().isEmpty()) {
            throw new CloseValidationException(data.violations());
        }
        // 先生成时间戳，保证冻结体与快照行的 generatedAt 完全一致
        LocalDateTime generatedAt = LocalDateTime.now();

        LeaderboardSnapshot snapshot = snapshotRepository.findByTournamentId(tournamentId)
                .orElseGet(LeaderboardSnapshot::new);
        snapshot.setTournament(locked);
        snapshot.setSnapshotType(LeaderboardSnapshot.TYPE_OFFICIAL);
        snapshot.setStatusSnapshot("已结束");
        snapshot.setFailureReasons(null);
        snapshot.setGeneratedAt(generatedAt);
        snapshot.setUpdatedAt(generatedAt);
        snapshotRepository.saveAndFlush(snapshot);

        locked.setStatus("已结束");
        tournamentRepository.saveAndFlush(locked);

        // 正式冻结体在状态翻转、版本自增定稿后组装，确保落库 JSON 里就是最终赛事版本号。
        LeaderboardView official = assemble(LeaderboardView.KIND_OFFICIAL, false, true, false,
                locked, snapshot.getVersion(), generatedAt, List.of(), data);
        snapshot.setOfficialPayloadJson(toJson(official));
        snapshotRepository.saveAndFlush(snapshot);
        return official;
    }

    /** 收口失败留痕：独立事务（REQUIRES_NEW 语义由新 TransactionTemplate 传播实现）。 */
    private void recordFailure(Long tournamentId, List<String> reasons) {
        transactionTemplate.executeWithoutResult(status -> {
            Tournament t = tournamentRepository.findById(tournamentId)
                    .orElseThrow(() -> new BizException("赛事不存在：" + tournamentId));
            LeaderboardSnapshot snapshot = snapshotRepository.findByTournamentId(tournamentId)
                    .orElseGet(LeaderboardSnapshot::new);
            snapshot.setTournament(t);
            snapshot.setSnapshotType(LeaderboardSnapshot.TYPE_FAILED);
            snapshot.setStatusSnapshot(t.getStatus());
            snapshot.setFailureReasons(writeReasons(reasons));
            snapshot.setOfficialPayloadJson(null);
            snapshot.setGeneratedAt(LocalDateTime.now());
            snapshot.setUpdatedAt(LocalDateTime.now());
            snapshotRepository.save(snapshot);
        });
    }

    // ================================================================
    // 核心计算：把当前成绩表汇总成名次（临时结果 / 正式冻结共用同一算法）
    // ================================================================

    /**
     * 汇总赛事当前全部成绩。violations 是收口阻断原因（精确到赛事 / 球员 / 洞号）；
     * 临时查询时 violations 转成非阻断 notices，不影响页面展示。
     */
    private Computation compute(Tournament tournament) {
        Long tid = tournament.getId();
        List<Hole> openHoles = holeRepository.findByStatus("开放").stream()
                .sorted(Comparator.comparing(Hole::getHoleNo))
                .toList();
        List<Integer> holeNos = openHoles.stream().map(Hole::getHoleNo).toList();
        Map<Long, Hole> holesById = holeRepository.findAll().stream()
                .collect(Collectors.toMap(Hole::getId, Function.identity(), (a, b) -> a));
        Map<Long, Player> playersById = playerRepository.findAll().stream()
                .collect(Collectors.toMap(Player::getId, Function.identity(), (a, b) -> a));
        List<TournamentEntry> entries = entryRepository.findByTournamentIdOrderByIdAsc(tid);

        List<String> violations = new ArrayList<>();
        String tLabel = "赛事「" + tournament.getName() + "」（" + tournament.getTournamentNo() + "）";
        if (entries.isEmpty()) {
            violations.add(tLabel + "没有任何参赛球员，无法生成正式排行榜");
        }
        if (openHoles.isEmpty()) {
            violations.add(tLabel + "当前没有任何「开放」球洞，无法界定应打洞数，无法生成正式排行榜");
        }

        // 球员 → （轮次 → （洞号 → 杆数)），只收集「球员与洞都存在」的成绩；
        // 同球员同轮同洞出现多条时全部视为非法（收口必须明确指出，不能悄悄取一条）。
        Map<Long, Map<Integer, Map<Integer, Integer>>> valid = new LinkedHashMap<>();
        Set<Long> entryPlayerIds = entries.stream()
                .map(e -> e.getPlayer().getId())
                .collect(Collectors.toSet());
        Map<Long, Integer> roundCountByPlayer = entries.stream()
                .collect(Collectors.toMap(e -> e.getPlayer().getId(),
                        TournamentEntry::getRoundCount, (a, b) -> a));

        // 先抓「成绩指向不存在的球员」这类孤儿成绩（实体导航会直接吞掉，必须用投影）。
        List<ScoreRepository.ScoreRow> rows = scoreRepository.findRowsByTournamentId(tid);
        Set<Long> scorePlayerIds = rows.stream().map(ScoreRepository.ScoreRow::getPlayerId)
                .collect(Collectors.toSet());
        for (Long pid : scorePlayerIds) {
            if (!playersById.containsKey(pid)) {
                String scope = rows.stream()
                        .filter(r -> Objects.equals(r.getPlayerId(), pid))
                        .map(r -> "第 " + r.getRoundNo() + " 轮第 " + holeLabel(holesById.get(r.getHoleId()), r.getHoleId()) + " 洞")
                        .distinct()
                        .collect(Collectors.joining("、"));
                violations.add(tLabel + "存在属于不存在球员（playerId=" + pid + "）的成绩：" + scope
                        + "，该成绩没有参赛主体，不能收口");
            }
        }

        for (ScoreRepository.ScoreRow row : rows) {
            Player player = playersById.get(row.getPlayerId());
            Hole hole = holesById.get(row.getHoleId());
            String pLabel = player == null
                    ? "不存在球员（playerId=" + row.getPlayerId() + "）"
                    : "球员「" + player.getName() + "」";
            String holeLabel = hole == null
                    ? "不存在洞（holeId=" + row.getHoleId() + "）"
                    : "第 " + hole.getHoleNo() + " 洞";

            if (player == null) {
                continue; // 孤儿球员已在上面汇总
            }
            if (!entryPlayerIds.contains(player.getId())) {
                violations.add(tLabel + pLabel + "不在参赛名单中，其第 " + row.getRoundNo()
                        + " 轮" + holeLabel + "成绩不能计入排行榜，请先移除或补报名");
                continue;
            }
            if (hole == null) {
                violations.add(tLabel + pLabel + "第 " + row.getRoundNo()
                        + " 轮有成绩指向" + holeLabel + "，洞号不合法");
                continue;
            }
            Integer maxRound = roundCountByPlayer.get(player.getId());
            if (row.getRoundNo() == null || row.getRoundNo() < 1) {
                violations.add(tLabel + pLabel + "存在轮次不合法（" + row.getRoundNo() + "）的成绩："
                        + holeLabel + " " + row.getStrokes() + " 杆");
                continue;
            }
            if (maxRound != null && row.getRoundNo() > maxRound) {
                violations.add(tLabel + pLabel + "本场只应打 " + maxRound + " 轮，存在第 "
                        + row.getRoundNo() + " 轮" + holeLabel + "成绩，轮次越界");
                continue;
            }
            if (!"开放".equals(hole.getStatus())) {
                violations.add(tLabel + pLabel + "第 " + row.getRoundNo() + " 轮" + holeLabel
                        + "当前为「" + hole.getStatus() + "」非应打球洞，其成绩（" + row.getStrokes()
                        + " 杆）不能计入正式排行榜");
                continue;
            }
            if (row.getStrokes() == null || row.getStrokes() < Dict.MIN_STROKES) {
                violations.add(tLabel + pLabel + "第 " + row.getRoundNo() + " 轮" + holeLabel
                        + "杆数不合法（" + row.getStrokes() + "，须 ≥ " + Dict.MIN_STROKES + "）");
                continue;
            }
            Integer prior = valid
                    .computeIfAbsent(player.getId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(row.getRoundNo(), k -> new HashMap<>())
                    .put(hole.getHoleNo(), row.getStrokes());
            if (prior != null) {
                violations.add(tLabel + pLabel + "第 " + row.getRoundNo() + " 轮" + holeLabel
                        + "存在重复成绩（" + prior + " 杆与 " + row.getStrokes()
                        + " 杆两条），收口前必须只保留确认后的一条");
            }
        }

        // 组装每个参赛名额的逐轮行；缺轮 / 缺洞在此识别（正式快照不允许，临时结果只提示）。
        List<LeaderboardRow> leaderboardRows = new ArrayList<>();
        for (TournamentEntry entry : entries) {
            Player player = playersById.get(entry.getPlayer().getId());
            if (player == null) {
                violations.add(tLabel + "参赛名单引用了不存在的球员（playerId="
                        + entry.getPlayer().getId() + "），无法生成排行榜");
                continue;
            }
            Map<Integer, Map<Integer, Integer>> byRound =
                    valid.getOrDefault(player.getId(), Map.of());
            List<LeaderboardView.Round> rounds = new ArrayList<>();
            boolean rowComplete = !openHoles.isEmpty();
            int totalStrokes = 0;
            int totalPar = 0;
            for (int round = 1; round <= entry.getRoundCount(); round++) {
                Map<Integer, Integer> byHole = byRound.getOrDefault(round, Map.of());
                List<Integer> missing = new ArrayList<>();
                Map<Integer, Integer> strokesByHole = new LinkedHashMap<>();
                int roundStrokes = 0;
                int roundPar = 0;
                for (Hole hole : openHoles) {
                    // par 无论是否已录入都计入总标准杆（缺洞只缺杆数，不缺标准杆口径）
                    roundPar += hole.getPar();
                    Integer strokes = byHole.get(hole.getHoleNo());
                    if (strokes == null) {
                        missing.add(hole.getHoleNo());
                        rowComplete = false;
                    } else {
                        strokesByHole.put(hole.getHoleNo(), strokes);
                        roundStrokes += strokes;
                    }
                }
                boolean roundComplete = missing.isEmpty();
                if (!roundComplete) {
                    // 缺洞是收口阻断原因；临时查询的 notices 在末尾统一取 violations 全量，不重复添加。
                    violations.add(tLabel + "球员「" + player.getName() + "」第 " + round
                            + " 轮缺少第 " + missing.stream().map(String::valueOf)
                            .collect(Collectors.joining("、")) + " 洞成绩");
                }
                totalStrokes += roundStrokes;
                totalPar += roundPar;
                rounds.add(new LeaderboardView.Round(round, strokesByHole,
                        roundComplete ? roundStrokes : null,
                        roundPar, roundComplete, List.copyOf(missing)));
            }
            leaderboardRows.add(new LeaderboardRow(player, rowComplete,
                    rowComplete ? totalStrokes : null, totalPar, rounds));
        }

        // 临时结果页面要提示全部收口阻断原因（缺洞 / 孤儿 / 越界 / 非法杆数等），
        // 但不阻断当前展示；正式收口时 violations 非空即整体回滚。
        List<String> notices = List.copyOf(violations);
        return new Computation(holeNos, leaderboardRows, violations, notices);
    }

    private String holeLabel(Hole hole, Long holeId) {
        return hole == null ? "不存在洞（holeId=" + holeId + "）" : String.valueOf(hole.getHoleNo());
    }

    /** 统一组装：排名计算 + 视图构造（临时 / 正式 / 失败 / 历史四种 kind 共用）。 */
    private LeaderboardView assemble(String kind, boolean provisional, boolean frozen, boolean legacy,
                                     Tournament tournament, Long snapshotVersion,
                                     LocalDateTime generatedAt, List<String> failureReasons,
                                     Computation data) {
        List<LeaderboardView.Row> rows = rankRows(data.rows());
        return new LeaderboardView(kind, provisional, frozen, legacy,
                tournament.getId(), tournament.getTournamentNo(), tournament.getName(),
                tournament.getStatus(), tournament.getVersion(), snapshotVersion,
                generatedAt, List.copyOf(failureReasons), data.notices(), data.holeNos(), rows);
    }

    /**
     * 名次规则：成绩齐全者按总杆升序（同杆同名次，并列后跳号）；
     * 未完赛者排在后面且不赋名次（临时结果可见，但不能被当成正式名次）。
     */
    private List<LeaderboardView.Row> rankRows(List<LeaderboardRow> rows) {
        List<LeaderboardRow> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator
                .comparing((LeaderboardRow r) -> !r.complete()) // 完整者在前
                .thenComparing(r -> r.complete() ? r.totalStrokes() : Integer.MAX_VALUE)
                .thenComparing(r -> r.player().getId()));

        List<LeaderboardView.Row> result = new ArrayList<>();
        long rank = 0;
        int completeSeen = 0;
        Integer lastStrokes = null;
        for (LeaderboardRow r : ordered) {
            if (r.complete()) {
                completeSeen++;
                if (lastStrokes == null || !lastStrokes.equals(r.totalStrokes())) {
                    rank = completeSeen;
                }
                lastStrokes = r.totalStrokes();
                result.add(new LeaderboardView.Row(rank, r.player().getId(), r.player().getName(),
                        r.totalStrokes(), r.totalPar(), r.totalStrokes() - r.totalPar(),
                        true, r.rounds()));
            } else {
                result.add(new LeaderboardView.Row(null, r.player().getId(), r.player().getName(),
                        null, r.totalPar(), null, false, r.rounds()));
            }
        }
        return result;
    }

    private LeaderboardView withRuntimeState(LeaderboardView frozen, Tournament tournament,
                                             LeaderboardSnapshot snapshot) {
        return new LeaderboardView(frozen.kind(), frozen.provisional(), frozen.frozen(), frozen.legacy(),
                tournament.getId(), frozen.tournamentNo(), frozen.tournamentName(),
                tournament.getStatus(), tournament.getVersion(), snapshot.getVersion(),
                frozen.generatedAt(), frozen.failureReasons(), frozen.notices(),
                frozen.holeNos(), frozen.rows());
    }

    // ================================================================
    // JSON 冻结 / 读取（正式快照一旦生成，只允许整体读出，不允许重算）
    // ================================================================

    private String toJson(LeaderboardView view) {
        try {
            return objectMapper.writeValueAsString(view);
        } catch (JsonProcessingException e) {
            throw new BizException("正式排行榜快照序列化失败：" + e.getMessage());
        }
    }

    private LeaderboardView readPayload(String json, Long tournamentId) {
        try {
            return objectMapper.readValue(json, LeaderboardView.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new BizException("赛事 " + tournamentId + " 的正式排行榜快照无法解析，已拒绝返回半成品数据");
        }
    }

    private String writeReasons(List<String> reasons) {
        try {
            return objectMapper.writeValueAsString(reasons);
        } catch (JsonProcessingException e) {
            throw new BizException("收口失败原因序列化失败：" + e.getMessage());
        }
    }

    private List<String> readReasons(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return List.of("（历史失败原因无法解析：" + e.getMessage() + "）");
        }
    }

    /** 计算中间结果。 */
    private record Computation(
            List<Integer> holeNos,
            List<LeaderboardRow> rows,
            List<String> violations,
            List<String> notices) {
    }

    /** 排名前的参赛名额汇总。 */
    private record LeaderboardRow(
            Player player,
            boolean complete,
            Integer totalStrokes,
            int totalPar,
            List<LeaderboardView.Round> rounds) {
    }
}
