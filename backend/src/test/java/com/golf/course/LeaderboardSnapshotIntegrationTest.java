package com.golf.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.golf.course.common.ConflictException;
import com.golf.course.dto.LeaderboardView;
import com.golf.course.entity.Hole;
import com.golf.course.entity.LeaderboardSnapshot;
import com.golf.course.entity.Player;
import com.golf.course.entity.Score;
import com.golf.course.entity.Tournament;
import com.golf.course.entity.TournamentEntry;
import com.golf.course.repository.HoleRepository;
import com.golf.course.repository.LeaderboardSnapshotRepository;
import com.golf.course.repository.PlayerRepository;
import com.golf.course.repository.ScoreRepository;
import com.golf.course.repository.TournamentEntryRepository;
import com.golf.course.repository.TournamentRepository;

/**
 * 排行榜快照一致性集成测试（H2 MySQL 模式）：
 * 临时口径、收口冻结、失败回滚、并发唯一快照、赛后补录拦截、历史兼容。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaderboardSnapshotIntegrationTest {

    @Autowired
    private TournamentRepository tournamentRepository;
    @Autowired
    private PlayerRepository playerRepository;
    @Autowired
    private HoleRepository holeRepository;
    @Autowired
    private ScoreRepository scoreRepository;
    @Autowired
    private TournamentEntryRepository entryRepository;
    @Autowired
    private LeaderboardSnapshotRepository snapshotRepository;
    @Autowired
    private LeaderboardSnapshotService service;
    @Autowired
    private TournamentService tournamentService;
    @Autowired
    private ScoreService scoreService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> openHoleIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 按外键依赖顺序清空全部业务表
        jdbc.execute("DELETE FROM handicap_history_round");
        jdbc.execute("DELETE FROM handicap_history");
        jdbc.execute("DELETE FROM handicap_run_error");
        jdbc.execute("DELETE FROM handicap_run");
        jdbc.execute("DELETE FROM handicap_rule");
        jdbc.execute("DELETE FROM scorecard_event");
        jdbc.execute("DELETE FROM scorecard_hole");
        jdbc.execute("DELETE FROM scorecard");
        jdbc.execute("DELETE FROM tournament_entry");
        jdbc.execute("DELETE FROM score");
        jdbc.execute("DELETE FROM leaderboard_snapshot");
        jdbc.execute("DELETE FROM leaderboard");
        jdbc.execute("DELETE FROM tee_time");
        jdbc.execute("DELETE FROM tournament");
        jdbc.execute("DELETE FROM player");
        jdbc.execute("DELETE FROM hole");

        int[][] pars = {{1, 4}, {2, 5}, {3, 3}, {4, 4}, {5, 3}};
        for (int[] hp : pars) {
            Hole h = new Hole();
            h.setHoleNo(hp[0]);
            h.setPar(hp[1]);
            h.setYardage(300 + hp[0] * 10);
            h.setStatus("开放");
            h.setCreatedAt(LocalDateTime.now());
            holeRepository.save(h);
            openHoleIds.add(h.getId());
        }
    }

    private Player mkPlayer(String no, String name, int handicap) {
        Player p = new Player();
        p.setPlayerNo(no);
        p.setName(name);
        p.setHandicap(handicap);
        p.setCreatedAt(LocalDateTime.now());
        return playerRepository.save(p);
    }

    private Tournament mkTournament(String no, String name, String status) {
        Tournament t = new Tournament();
        t.setTournamentNo(no);
        t.setName(name);
        t.setStartDate(LocalDate.of(2026, 10, 10));
        t.setEndDate(LocalDate.of(2026, 10, 12));
        t.setStatus(status);
        t.setLocation("A场");
        t.setCourseRating(new BigDecimal("28.8"));
        t.setSlopeRating(116);
        t.setCreatedAt(LocalDateTime.now());
        return tournamentRepository.save(t);
    }

    private void entry(Tournament t, Player p, int rounds) {
        TournamentEntry e = new TournamentEntry();
        e.setTournament(t);
        e.setPlayer(p);
        e.setRoundCount(rounds);
        e.setCreatedAt(LocalDateTime.now());
        entryRepository.save(e);
    }

    private void score(Tournament t, Player p, int round, int holeSeq, int strokes) {
        scoreRepository.saveAndFlush(inputScore(t, p, round, holeSeq, strokes));
    }

    private Score inputScore(Tournament t, Player p, int round, int holeSeq, int strokes) {
        Score s = new Score();
        s.setTournament(t);
        s.setPlayer(p);
        s.setHole(holeRepository.findById(openHoleIds.get(holeSeq - 1)).orElseThrow());
        s.setRoundNo(round);
        s.setStrokes(strokes);
        s.setCreatedAt(LocalDateTime.now());
        return s;
    }

    // ---------------- 1. 报名中 / 进行中：只有临时结果 ----------------

    @Test
    void provisionalLeaderboard_isLabeledAndTracksLiveScores() {
        Tournament t = mkTournament("T-P1", "临时赛事", "报名中");
        Player a = mkPlayer("TP-1", "甲", 10);
        Player b = mkPlayer("TP-2", "乙", 12);
        entry(t, a, 1);
        entry(t, b, 1);
        score(t, a, 1, 1, 4);
        score(t, a, 1, 2, 5);

        LeaderboardView v = service.getView(t.getId());
        assertThat(v.kind()).isEqualTo(LeaderboardView.KIND_PROVISIONAL);
        assertThat(v.provisional()).isTrue();
        assertThat(v.frozen()).isFalse();
        // 两人都未完赛：临时结果没有正式名次
        assertThat(v.rows()).allSatisfy(r -> assertThat(r.rank()).isNull());
        assertThat(v.notices()).isNotEmpty();
        // 临时结果不落库
        assertThat(snapshotRepository.findByTournamentId(t.getId())).isEmpty();

        // 赛事进入「进行中」，补完成绩，临时排名随之变化
        t.setStatus("进行中");
        tournamentRepository.save(t);
        for (int h = 3; h <= 5; h++) {
            score(t, a, 1, h, 3 + h); // 6、7、8 → 甲合计 4+5+6+7+8 = 30
        }
        for (int h = 1; h <= 5; h++) {
            score(t, b, 1, h, 5); // 乙合计 25，排名第一
        }
        LeaderboardView v2 = service.getView(t.getId());
        assertThat(v2.kind()).isEqualTo(LeaderboardView.KIND_PROVISIONAL);
        assertThat(v2.rows()).hasSize(2);
        assertThat(v2.rows().get(0).playerName()).isEqualTo("乙");
        assertThat(v2.rows().get(0).rank()).isEqualTo(1L);
        assertThat(v2.rows().get(1).playerName()).isEqualTo("甲");
        assertThat(v2.rows().get(1).rank()).isEqualTo(2L);
        assertThat(v2.notices()).isEmpty();
    }

    // ---------------- 2. 收口成功：唯一正式快照冻结 ----------------

    @Test
    void finalize_freezesOfficialSnapshot_andLaterScoreChangesDoNotAffectRanking() throws Exception {
        Tournament t = mkTournament("T-O1", "收口赛事", "进行中");
        Player a = mkPlayer("TO-1", "甲", 10);
        Player b = mkPlayer("TO-2", "乙", 12);
        entry(t, a, 1);
        entry(t, b, 1);
        for (int h = 1; h <= 5; h++) {
            score(t, a, 1, h, 4); // 20 杆（par 19）+1
            score(t, b, 1, h, 5); // 25 杆 +6
        }

        LeaderboardView official = service.finalizeOfficial(t.getId(), null);
        assertThat(official.kind()).isEqualTo(LeaderboardView.KIND_OFFICIAL);
        assertThat(official.frozen()).isTrue();
        assertThat(official.rows().get(0).playerName()).isEqualTo("甲");
        assertThat(official.rows().get(0).totalStrokes()).isEqualTo(20);
        assertThat(official.rows().get(0).toPar()).isEqualTo(1);
        assertThat(official.rows().get(1).totalStrokes()).isEqualTo(25);

        // 赛事已结束、快照落库
        assertThat(tournamentRepository.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo("已结束");
        LeaderboardSnapshot row = snapshotRepository.findByTournamentId(t.getId()).orElseThrow();
        assertThat(row.getSnapshotType()).isEqualTo(LeaderboardSnapshot.TYPE_OFFICIAL);
        assertThat(row.getOfficialPayloadJson()).isNotBlank();

        // 赛后补录成绩被拒绝（边界）
        assertThatThrownBy(() -> scoreService.record(inputScore(t, a, 1, 1, 2)))
                .hasMessageContaining("已结束");

        // 即使直接把成绩表删空，正式快照名次也不变（只读冻结体，不回查成绩）
        jdbc.update("DELETE FROM score WHERE tournament_id = ?", t.getId());
        LeaderboardView reread = service.getView(t.getId());
        assertThat(reread.kind()).isEqualTo(LeaderboardView.KIND_OFFICIAL);
        assertThat(reread.frozen()).isTrue();
        assertThat(reread.rows()).hasSize(2);
        assertThat(reread.rows().get(0).totalStrokes()).isEqualTo(20);
        assertThat(reread.rows().get(1).totalStrokes()).isEqualTo(25);
        assertThat(reread.rows().get(0).rounds().get(0).strokesByHole())
                .containsAllEntriesOf(Map.of(1, 4, 2, 4, 3, 4, 4, 4, 5, 4))
                .hasSize(5);

        // 冻结体自包含最终版本号：每次重新读取都和库里版本一致，不再因外部数据变化而漂移
        assertThat(reread.tournamentVersion()).isEqualTo(
                tournamentRepository.findById(t.getId()).orElseThrow().getVersion());
        assertThat(reread.tournamentStatus()).isEqualTo("已结束");
        JsonNode payload = objectMapper.readTree(
                snapshotRepository.findByTournamentId(t.getId()).orElseThrow().getOfficialPayloadJson());
        assertThat(payload.path("frozen").asBoolean()).isTrue();
        assertThat(payload.path("tournamentStatus").asText()).isEqualTo("已结束");
        assertThat(payload.path("rows").get(0).path("totalStrokes").asInt()).isEqualTo(20);

        // 重复收口被拒绝：唯一正式快照
        assertThatThrownBy(() -> service.finalizeOfficial(t.getId(), null))
                .isInstanceOf(ConflictException.class);
    }

    // ---------------- 3. 收口失败：缺成绩精确到洞号，回滚，临时排名仍可查 ----------------

    @Test
    void finalize_missingScores_failsNamingHole_rollsBackAndKeepsProvisional() {
        Tournament t = mkTournament("T-F1", "失败赛事A", "进行中");
        Player a = mkPlayer("TF-0", "甲", 10);
        Player b = mkPlayer("TF-9", "乙", 12);
        entry(t, a, 1);
        entry(t, b, 1);
        for (int h = 1; h <= 5; h++) {
            score(t, a, 1, h, 4);
        }
        // 乙只录了 1、2 洞，缺 3、4、5 洞
        score(t, b, 1, 1, 6);
        score(t, b, 1, 2, 6);

        assertThatThrownBy(() -> service.finalizeOfficial(t.getId(), null))
                .isInstanceOfSatisfying(LeaderboardSnapshotService.CloseValidationException.class,
                        ex -> assertThat(ex.reasons())
                                .anyMatch(r -> r.contains("失败赛事A") && r.contains("乙")
                                        && r.contains("第 1 轮") && r.contains("3")
                                        && r.contains("洞成绩")));

        // 回滚：赛事仍进行中；无正式行；有失败留痕；不留半份结果
        assertThat(tournamentRepository.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo("进行中");
        LeaderboardSnapshot failRow = snapshotRepository.findByTournamentId(t.getId()).orElseThrow();
        assertThat(failRow.getSnapshotType()).isEqualTo(LeaderboardSnapshot.TYPE_FAILED);
        assertThat(failRow.getOfficialPayloadJson()).isNull();

        // 临时排名仍可查，且带失败原因
        LeaderboardView v = service.getView(t.getId());
        assertThat(v.kind()).isEqualTo(LeaderboardView.KIND_FAILED);
        assertThat(v.provisional()).isTrue();
        assertThat(v.failureReasons()).isNotEmpty();
        assertThat(v.rows()).hasSize(2);
    }

    // ---------------- 4. 收口失败：非法杆数，修复后可重新收口 ----------------

    @Test
    void finalize_illegalStrokes_failNamingPlayerHole_thenRetrySucceeds() {
        Tournament t = mkTournament("T-F2", "失败赛事B", "进行中");
        Player a = mkPlayer("TF-1", "甲", 10);
        entry(t, a, 1);
        for (int h = 1; h <= 4; h++) {
            score(t, a, 1, h, 4);
        }
        Score bad = inputScore(t, a, 1, 5, 0); // 非法杆数
        scoreRepository.saveAndFlush(bad);

        assertThatThrownBy(() -> service.finalizeOfficial(t.getId(), null))
                .isInstanceOfSatisfying(LeaderboardSnapshotService.CloseValidationException.class,
                        ex -> assertThat(ex.reasons())
                                .anyMatch(r -> r.contains("失败赛事B") && r.contains("甲")
                                        && r.contains("第 5 洞") && r.contains("杆数不合法")));

        // 修复后重新收口：失败留痕被正式快照覆盖
        bad.setStrokes(4);
        scoreRepository.saveAndFlush(bad);
        LeaderboardView official = service.finalizeOfficial(t.getId(), null);
        assertThat(official.frozen()).isTrue();
        LeaderboardSnapshot okRow = snapshotRepository.findByTournamentId(t.getId()).orElseThrow();
        assertThat(okRow.getSnapshotType()).isEqualTo(LeaderboardSnapshot.TYPE_OFFICIAL);
        assertThat(okRow.getFailureReasons()).isNull();
    }

    // ---------------- 5. 收口失败：非参赛球员 / 不存在洞的孤儿成绩 ----------------

    @Test
    void finalize_nonEntryOrOrphanScores_areRejected() {
        Tournament t = mkTournament("T-F3", "失败赛事C", "进行中");
        Player a = mkPlayer("TF-3", "甲", 10);
        Player outsider = mkPlayer("TF-4", "圈外人", 20);
        entry(t, a, 1);
        for (int h = 1; h <= 5; h++) {
            score(t, a, 1, h, 4);
            score(t, outsider, 1, h, 7); // 不在参赛名单
        }

        assertThatThrownBy(() -> service.finalizeOfficial(t.getId(), null))
                .isInstanceOfSatisfying(LeaderboardSnapshotService.CloseValidationException.class,
                        ex -> assertThat(ex.reasons())
                                .anyMatch(r -> r.contains("圈外人") && r.contains("参赛名单")));

        // 不存在球员的孤儿成绩：生产库 score 表没有外键约束，模拟这种历史脏数据。
        // H2 自动建表会生成外键，先对当前会话关闭参照完整性再插入。
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        jdbc.update("INSERT INTO score (player_id, tournament_id, hole_id, round_no, strokes, created_at) "
                + "VALUES (999999, ?, ?, 1, 4, CURRENT_TIMESTAMP)", t.getId(), openHoleIds.get(0));
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        assertThatThrownBy(() -> service.finalizeOfficial(t.getId(), null))
                .isInstanceOfSatisfying(LeaderboardSnapshotService.CloseValidationException.class,
                        ex -> assertThat(ex.reasons())
                                .anyMatch(r -> r.contains("不存在球员") && r.contains("999999")));
    }

    // ---------------- 6. 并发收口：只允许一个版本成为正式快照 ----------------

    @Test
    void concurrentFinalize_onlyOneOfficialSnapshot_winnerFreezesLosersConflict() throws Exception {
        Tournament t = mkTournament("T-C1", "并发赛事", "进行中");
        Player a = mkPlayer("TC-1", "甲", 10);
        entry(t, a, 1);
        for (int h = 1; h <= 5; h++) {
            score(t, a, 1, h, 4);
        }

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<String> loserErrors = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    service.finalizeOfficial(t.getId(), null);
                    success.incrementAndGet();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    // 乐观锁冲突或行锁等待失败：竞争失败方都必须被拒绝，不能产生第二份正式快照
                    rejected.incrementAndGet();
                    loserErrors.add(e.getClass().getSimpleName() + ":" + e.getMessage());
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(success.get()).as("loser errors: %s", loserErrors).isEqualTo(1);
        assertThat(rejected.get()).as("loser errors: %s", loserErrors).isEqualTo(threads - 1);
        assertThat(snapshotRepository.findAll()).hasSize(1);
        assertThat(snapshotRepository.findAll().get(0).getSnapshotType())
                .isEqualTo(LeaderboardSnapshot.TYPE_OFFICIAL);
        assertThat(tournamentRepository.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo("已结束");
    }

    // ---------------- 7. 过期版本：409 数据已变化 ----------------

    @Test
    void staleExpectedVersion_isRejectedAsConflict() {
        Tournament t = mkTournament("T-V1", "版本赛事", "进行中");
        Player a = mkPlayer("TV-1", "甲", 10);
        entry(t, a, 1);
        for (int h = 1; h <= 5; h++) {
            score(t, a, 1, h, 4);
        }
        long stale = t.getVersion() - 1;
        assertThatThrownBy(() -> service.finalizeOfficial(t.getId(), stale))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("数据已变化");
        assertThat(tournamentRepository.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo("进行中");
    }

    // ---------------- 8. HTTP 链路：400 失败原因 + 排行榜接口口径 ----------------

    @Test
    void http_finalizeFailReturns400WithReasons_andLeaderboardEndpointShowsFailure() throws Exception {
        Tournament t = mkTournament("T-H1", "接口赛事", "进行中");
        Player a = mkPlayer("TH-1", "甲", 10);
        entry(t, a, 1);
        // 一洞都没录：必然收口失败
        ResponseEntity<String> resp = rest.postForEntity(
                "/api/tournaments/" + t.getId() + "/leaderboard/finalize", null, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.path("ok").asBoolean()).isFalse();
        assertThat(body.path("message").asText()).contains("甲").contains("接口赛事");

        ResponseEntity<String> lb = rest.getForEntity(
                "/api/tournaments/" + t.getId() + "/leaderboard", String.class);
        JsonNode lbBody = objectMapper.readTree(lb.getBody());
        assertThat(lbBody.path("data").path("kind").asText()).isEqualTo("生成失败");
        assertThat(lbBody.path("data").path("failureReasons")).isNotEmpty();
        assertThat(lbBody.path("data").path("provisional").asBoolean()).isTrue();
    }

    // ---------------- 9. 历史兼容：修复前已结束、无快照的旧赛事 ----------------

    @Test
    void legacyFinishedTournamentWithoutSnapshot_isReadonlyHistoricalView() {
        Tournament t = mkTournament("T-L1", "历史赛事", "已结束");
        Player a = mkPlayer("TL-1", "老甲", 10);
        entry(t, a, 1);
        for (int h = 1; h <= 5; h++) {
            score(t, a, 1, h, 4);
        }
        LeaderboardView v = service.getView(t.getId());
        assertThat(v.kind()).isEqualTo(LeaderboardView.KIND_LEGACY);
        assertThat(v.legacy()).isTrue();
        assertThat(v.frozen()).isFalse();
        assertThat(v.rows()).hasSize(1);
        assertThat(snapshotRepository.findByTournamentId(t.getId())).isEmpty();
    }

    // ---------------- 10. advance 推进路径：开始 → 结束（收口）状态与快照一致 ----------------

    @Test
    void advanceToFinished_throughTournamentService_generatesFrozenSnapshot() {
        Tournament t = mkTournament("T-A1", "推进赛事", "报名中");
        Player a = mkPlayer("TA-1", "甲", 10);
        entry(t, a, 1);
        for (int h = 1; h <= 5; h++) {
            score(t, a, 1, h, 4);
        }

        // 报名中 → 进行中：普通推进，返回 null（控制器回读实体）
        assertThat(tournamentService.advance(t.getId(), "进行中", null)).isNull();
        assertThat(tournamentRepository.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo("进行中");

        // 进行中 → 已结束：返回冻结的正式排行榜视图
        LeaderboardView official = tournamentService.advance(t.getId(), "已结束", null);
        assertThat(official).isNotNull();
        assertThat(official.frozen()).isTrue();
        assertThat(tournamentRepository.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo("已结束");
        assertThat(snapshotRepository.findByTournamentId(t.getId()).orElseThrow().getSnapshotType())
                .isEqualTo(LeaderboardSnapshot.TYPE_OFFICIAL);

        // 再次推进已结束赛事被状态机规则拒绝
        assertThatThrownBy(() -> tournamentService.advance(t.getId(), "已结束", null))
                .hasMessageContaining("不允许");
    }
}
