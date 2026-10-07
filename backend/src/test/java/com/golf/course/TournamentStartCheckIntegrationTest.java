package com.golf.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
import com.golf.course.common.StartBlockedException;
import com.golf.course.common.StartConflictException;
import com.golf.course.dto.StartCheckView;
import com.golf.course.entity.Course;
import com.golf.course.entity.Hole;
import com.golf.course.entity.Player;
import com.golf.course.entity.Scorecard;
import com.golf.course.entity.Tournament;
import com.golf.course.entity.TournamentEntry;
import com.golf.course.entity.TournamentStartCheck;
import com.golf.course.repository.CourseRepository;
import com.golf.course.repository.HoleRepository;
import com.golf.course.repository.PlayerRepository;
import com.golf.course.repository.ScorecardRepository;
import com.golf.course.repository.TournamentEntryRepository;
import com.golf.course.repository.TournamentRepository;
import com.golf.course.repository.TournamentStartCheckRepository;

/**
 * 赛事开赛前球场可用性校验集成测试（H2 MySQL 模式）：
 * 阻断口径（维护 / 封闭 / 未配置 / 基础数据不完整 / 未绑定球场）、失败原子恢复、
 * 修复后按最新版本重试、并发只允许一次成功、过期版本 409、开赛后冻结不随后续改洞变化、
 * 以及 HTTP 400 / 409 携带当前赛事与球洞状态。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TournamentStartCheckIntegrationTest {

    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private HoleRepository holeRepository;
    @Autowired private PlayerRepository playerRepository;
    @Autowired private TournamentEntryRepository entryRepository;
    @Autowired private ScorecardRepository cardRepository;
    @Autowired private TournamentStartCheckRepository checkRepository;
    @Autowired private TournamentStartService startService;
    @Autowired private TournamentService tournamentService;
    @Autowired private HoleService holeService;
    @Autowired private ScorecardService scorecardService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    private Long courseId;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM handicap_history_round");
        jdbc.execute("DELETE FROM handicap_history");
        jdbc.execute("DELETE FROM handicap_run_error");
        jdbc.execute("DELETE FROM handicap_run");
        jdbc.execute("DELETE FROM handicap_rule");
        jdbc.execute("DELETE FROM scorecard_event");
        jdbc.execute("DELETE FROM scorecard_hole");
        jdbc.execute("DELETE FROM scorecard");
        jdbc.execute("DELETE FROM tournament_entry");
        jdbc.execute("DELETE FROM tournament_start_check");
        jdbc.execute("DELETE FROM score");
        jdbc.execute("DELETE FROM leaderboard_snapshot");
        jdbc.execute("DELETE FROM leaderboard");
        jdbc.execute("DELETE FROM tee_time");
        jdbc.execute("DELETE FROM tournament");
        jdbc.execute("DELETE FROM player");
        jdbc.execute("DELETE FROM hole");
        jdbc.execute("DELETE FROM course");

        Course c = new Course();
        c.setCourseNo("C-9");
        c.setName("测试9洞场");
        c.setHoleCount(9);
        c.setCreatedAt(LocalDateTime.now());
        courseId = courseRepository.save(c).getId();
    }

    private void hole(int no, int par, int yardage, String status) {
        Hole h = new Hole();
        h.setCourse(courseRepository.findById(courseId).orElseThrow());
        h.setHoleNo(no);
        h.setPar(par);
        h.setYardage(yardage);
        h.setStatus(status);
        h.setCreatedAt(LocalDateTime.now());
        holeRepository.save(h);
    }

    /** 9 洞全部合法开放 */
    private void allOpen() {
        int[] pars = {4, 5, 3, 4, 4, 3, 5, 4, 3};
        for (int i = 1; i <= 9; i++) {
            hole(i, pars[i - 1], 300 + i * 10, "开放");
        }
    }

    private Tournament tournament(String no, String name, boolean bindCourse) {
        Tournament t = new Tournament();
        t.setTournamentNo(no);
        t.setName(name);
        if (bindCourse) {
            t.setCourse(courseRepository.findById(courseId).orElseThrow());
        }
        t.setStatus("报名中");
        t.setCreatedAt(LocalDateTime.now());
        return tournamentRepository.save(t);
    }

    private Player player(String no, String name) {
        Player p = new Player();
        p.setPlayerNo(no);
        p.setName(name);
        p.setHandicap(10);
        p.setCreatedAt(LocalDateTime.now());
        return playerRepository.save(p);
    }

    // ---------------- 1. 预检：可开赛 / 被阻止（纯查询不留痕） ----------------

    @Test
    void checkView_readyWhenAllOpen_blockedWhenMaintained_andLeavesNoTrace() {
        allOpen();
        Tournament t = tournament("T-R1", "可开赛赛事", true);

        StartCheckView ready = startService.getView(t.getId());
        assertThat(ready.phase()).isEqualTo(StartCheckView.PHASE_READY);
        assertThat(ready.canStart()).isTrue();
        assertThat(ready.blocked()).isFalse();
        assertThat(ready.eligibleHoleNos()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
        // 纯查询不落库
        assertThat(checkRepository.findByTournamentId(t.getId())).isEmpty();

        holeRepository.findByCourseIdAndHoleNo(courseId, 5).ifPresent(h -> {
            h.setStatus("维护");
            holeRepository.save(h);
        });
        StartCheckView blocked = startService.getView(t.getId());
        assertThat(blocked.phase()).isEqualTo(StartCheckView.PHASE_BLOCKED);
        assertThat(blocked.canStart()).isFalse();
        assertThat(blocked.blockedHoles()).hasSize(1);
        StartCheckView.BlockedHole b = blocked.blockedHoles().get(0);
        assertThat(b.holeNo()).isEqualTo(5);
        assertThat(b.currentStatus()).isEqualTo("维护");
        assertThat(b.fixHint()).contains("开放");
        // 预检依旧不留痕
        assertThat(checkRepository.findByTournamentId(t.getId())).isEmpty();
    }

    // ---------------- 2. 阻断口径：维护 / 封闭 / 未配置 / 基础数据不完整 / 未绑定 ----------------

    @Test
    void start_blocksOnClosedMissingIllegalParAndYardage_listingHoleNoStatusAndHint() {
        // 1~4 开放；第 5 洞封闭；6~8 开放；第 9 洞不配置；第 7 洞 par 非法、第 8 洞码距非法
        int[] pars = {4, 5, 3, 4, 4, 3, 9, 4, 3};
        for (int i = 1; i <= 8; i++) {
            int yard = i == 8 ? 0 : 300 + i * 10;
            hole(i, pars[i - 1], yard, i == 5 ? "封闭" : "开放");
        }
        Tournament t = tournament("T-B1", "阻断赛事", true);

        assertThatThrownBy(() -> startService.start(t.getId(), null))
                .isInstanceOfSatisfying(StartBlockedException.class, ex -> {
                    StartCheckView v = ex.view();
                    assertThat(v.blockedHoles()).hasSize(4);
                    assertThat(v.blockedHoles()).anyMatch(b -> b.holeNo() == 5
                            && "封闭".equals(b.currentStatus()));
                    assertThat(v.blockedHoles()).anyMatch(b -> b.holeNo() == 7
                            && "基础数据不完整".equals(b.currentStatus())
                            && b.reason().contains("标准杆"));
                    assertThat(v.blockedHoles()).anyMatch(b -> b.holeNo() == 8
                            && "基础数据不完整".equals(b.currentStatus())
                            && b.reason().contains("码距"));
                    assertThat(v.blockedHoles()).anyMatch(b -> b.holeNo() == 9
                            && "未配置".equals(b.currentStatus())
                            && b.fixHint().contains("补建第 9 洞"));
                });

        // 赛事仍报名中；留下「被阻止」留痕；没有可开赛记录
        Tournament reread = tournamentRepository.findById(t.getId()).orElseThrow();
        assertThat(reread.getStatus()).isEqualTo("报名中");
        TournamentStartCheck row = checkRepository.findByTournamentId(t.getId()).orElseThrow();
        assertThat(row.getCheckType()).isEqualTo(TournamentStartCheck.TYPE_BLOCKED);
        assertThat(row.getEligibleHoleNos()).isNull();
        assertThat(row.getBlockedHolesJson()).contains("封闭").contains("未配置");
    }

    @Test
    void start_withoutCourseBinding_isBlockedAsIncompleteBaseData() {
        allOpen();
        Tournament t = tournament("T-B2", "未绑定球场赛事", false);
        assertThatThrownBy(() -> startService.start(t.getId(), null))
                .isInstanceOfSatisfying(StartBlockedException.class, ex -> {
                    assertThat(ex.view().blockedHoles()).hasSize(1);
                    StartCheckView.BlockedHole b = ex.view().blockedHoles().get(0);
                    assertThat(b.holeNo()).isNull();
                    assertThat(b.currentStatus()).isEqualTo("基础数据不完整");
                    assertThat(b.reason()).contains("未绑定球场");
                    assertThat(ex.view().courseId()).isNull();
                });
        assertThat(tournamentRepository.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo("报名中");
    }

    // ---------------- 3. 失败原子恢复：赛事仍报名中，名单与成绩草稿不被清掉 ----------------

    @Test
    void failedStart_keepsRegistrationEntriesAndScoreDrafts_noHalfStartedState() {
        allOpen();
        Tournament t = tournament("T-A1", "原子恢复赛事", true);
        Player p = player("PA-1", "甲");
        TournamentEntry e = new TournamentEntry();
        e.setTournament(t);
        e.setPlayer(p);
        e.setRoundCount(1);
        e.setCreatedAt(LocalDateTime.now());
        entryRepository.save(e);
        // 记分员已建好一张成绩草稿
        Scorecard draft = scorecardService.getOrCreateDraft(t.getId(), p.getId(), 1);

        // 把一洞改维护，开赛失败
        Hole h5 = holeRepository.findByCourseIdAndHoleNo(courseId, 5).orElseThrow();
        h5.setStatus("维护");
        holeRepository.save(h5);

        assertThatThrownBy(() -> tournamentService.advance(t.getId(), "进行中", null))
                .isInstanceOf(StartBlockedException.class);

        assertThat(tournamentRepository.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo("报名中");
        // 名单仍在
        assertThat(entryRepository.findByTournamentIdOrderByIdAsc(t.getId())).hasSize(1);
        // 草稿仍在且状态未被改动
        Scorecard draftReread = cardRepository.findById(draft.getId()).orElseThrow();
        assertThat(draftReread.getStatus()).isEqualTo("草稿");
    }

    // ---------------- 4. 修复后重试：读取最新球洞版本，旧失败结果被覆盖 ----------------

    @Test
    void retryAfterFix_readsLatestHoleVersion_andOverwritesBlockedTrace() {
        allOpen();
        Tournament t = tournament("T-R2", "重试赛事", true);
        Hole h9 = holeRepository.findByCourseIdAndHoleNo(courseId, 9).orElseThrow();
        h9.setStatus("维护");
        holeRepository.save(h9);

        assertThatThrownBy(() -> startService.start(t.getId(), null))
                .isInstanceOf(StartBlockedException.class);
        TournamentStartCheck blockedRow = checkRepository.findByTournamentId(t.getId()).orElseThrow();
        assertThat(blockedRow.getCheckType()).isEqualTo(TournamentStartCheck.TYPE_BLOCKED);

        // 管理员修复：第 9 洞改回开放（维护会让球洞版本 +1）
        holeService.updateStatus(h9.getId(), "开放", null);

        StartCheckView started = startService.start(t.getId(), null);
        assertThat(started.phase()).isEqualTo(StartCheckView.PHASE_STARTED);
        assertThat(started.frozen()).isTrue();
        assertThat(started.eligibleHoleNos()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);

        Tournament ok = tournamentRepository.findById(t.getId()).orElseThrow();
        assertThat(ok.getStatus()).isEqualTo("进行中");
        // 同一条留痕被整行覆盖为「可开赛」，不残留旧失败明细
        List<TournamentStartCheck> rows = checkRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getCheckType()).isEqualTo(TournamentStartCheck.TYPE_PASSED);
        assertThat(rows.get(0).getBlockedHolesJson()).isNull();
        assertThat(rows.get(0).getEligibleHoleNos()).isEqualTo("1,2,3,4,5,6,7,8,9");
    }

    // ---------------- 5. 开赛后冻结：之后改维护 / 封闭不静默改变本轮 ----------------

    @Test
    void afterStarted_laterHoleMaintenanceDoesNotChangeThisRound_butNewTournamentRechecks() {
        allOpen();
        Tournament t = tournament("T-FZ", "冻结赛事", true);
        startService.start(t.getId(), null);

        // 开赛后把第 3 洞改为维护、第 6 洞封闭
        Hole h3 = holeRepository.findByCourseIdAndHoleNo(courseId, 3).orElseThrow();
        h3.setStatus("维护");
        holeRepository.saveAndFlush(h3);
        Hole h6 = holeRepository.findByCourseIdAndHoleNo(courseId, 6).orElseThrow();
        h6.setStatus("封闭");
        holeRepository.saveAndFlush(h6);

        // 已开赛视图仍是「已开始」，冻结洞集 1~9 不变
        StartCheckView v = startService.getView(t.getId());
        assertThat(v.phase()).isEqualTo(StartCheckView.PHASE_STARTED);
        assertThat(v.eligibleHoleNos()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
        // 本轮应打球洞（排行榜 / 记分卡口径）仍包含被改成维护 / 封闭的洞
        List<Hole> eligible = startService.eligibleHolesFor(
                tournamentRepository.findById(t.getId()).orElseThrow());
        assertThat(eligible).extracting(Hole::getHoleNo).contains(3, 6);

        // 后续一场新赛事必须按最新球洞重新校验 → 被第 3、6 洞阻止
        Tournament t2 = tournament("T-FZ2", "后续新赛事", true);
        StartCheckView v2 = startService.getView(t2.getId());
        assertThat(v2.phase()).isEqualTo(StartCheckView.PHASE_BLOCKED);
        assertThat(v2.blockedHoles()).extracting(StartCheckView.BlockedHole::holeNo)
                .containsExactlyInAnyOrder(3, 6);
    }

    // ---------------- 6. 多人同时开赛：只允许一次成功，其余 409 ----------------

    @Test
    void concurrentStart_onlyOneSucceeds_losersGetConflictAndCurrentState() throws Exception {
        allOpen();
        Tournament t = tournament("T-C1", "并发开赛", true);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    startLatch.await();
                    startService.start(t.getId(), null);
                    success.incrementAndGet();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (StartConflictException | ConflictException ce) {
                    conflict.incrementAndGet();
                    errors.add("conflict:" + ce.getMessage());
                } catch (RuntimeException re) {
                    errors.add(re.getClass().getSimpleName() + ":" + re.getMessage());
                }
            }));
        }
        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(success.get()).as("errors=%s", errors).isEqualTo(1);
        assertThat(conflict.get()).as("errors=%s", errors).isEqualTo(threads - 1);
        assertThat(tournamentRepository.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo("进行中");
        assertThat(checkRepository.findAll()).hasSize(1);
        assertThat(checkRepository.findAll().get(0).getCheckType())
                .isEqualTo(TournamentStartCheck.TYPE_PASSED);
    }

    // ---------------- 7. 过期版本：409 并返回当前赛事与球洞状态 ----------------

    @Test
    void staleExpectedVersion_returnsConflictWithFreshView() {
        allOpen();
        Tournament t = tournament("T-V1", "版本赛事", true);
        long stale = t.getVersion();
        // 他人先开赛成功，赛事版本前进
        startService.start(t.getId(), null);

        assertThatThrownBy(() -> startService.start(t.getId(), stale))
                .isInstanceOfSatisfying(StartConflictException.class, ex -> {
                    assertThat(ex.getMessage()).contains("数据已变化");
                    assertThat(ex.view().tournamentStatus()).isEqualTo("进行中");
                    assertThat(ex.view().phase()).isEqualTo(StartCheckView.PHASE_STARTED);
                });
    }

    @Test
    void concurrentHoleMaintenance_staleVersionIsRejectedWithConflict() {
        allOpen();
        Tournament t = tournament("T-V2", "球洞并发", true);
        Hole h1 = holeRepository.findByCourseIdAndHoleNo(courseId, 1).orElseThrow();
        long holeVersion = h1.getVersion();

        // 管理员 A 已把第 1 洞改为维护（版本 +1），管理员 B 用旧版本点「改回开放」
        holeService.updateStatus(h1.getId(), "维护", null);
        assertThatThrownBy(() -> holeService.updateStatus(h1.getId(), "开放", holeVersion))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("球洞数据已变化");
        // 旧版本失败没有把洞静默改回
        assertThat(holeRepository.findById(h1.getId()).orElseThrow().getStatus())
                .isEqualTo("维护");
    }

    // ---------------- 8. 开赛与球洞维护并发：维护成功后旧开赛按最新版本被阻止 ----------------

    @Test
    void startConcurrentlyWithHoleMaintenance_latestVersionWins() throws Exception {
        allOpen();
        Tournament t = tournament("T-C2", "开赛遇上维护", true);

        // 两个任务在同一开赛事务窗口并发：A 尝试开赛；B 在 A 之前把第 5 洞改为维护。
        // 串行化后必然只有一种合法结局：要么 B 的维护先提交（A 读到最新版本 → 被阻止，赛事仍报名中），
        // 要么 A 先开赛成功（B 的维护发生在开赛后 → 本轮冻结口径不变，赛事进行中）。
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);
        List<String> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        Future<?> fStart = pool.submit(() -> {
            try {
                latch.await();
                startService.start(t.getId(), null);
                outcomes.add("started");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (StartBlockedException be) {
                outcomes.add("blocked");
            }
        });
        Future<?> fMaint = pool.submit(() -> {
            try {
                latch.await();
                Hole h5 = holeRepository.findByCourseIdAndHoleNo(courseId, 5).orElseThrow();
                holeService.updateStatus(h5.getId(), "维护", null);
                outcomes.add("maintained");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
        latch.countDown();
        fStart.get();
        fMaint.get();
        pool.shutdown();

        String status = tournamentRepository.findById(t.getId()).orElseThrow().getStatus();
        StartCheckView finalView = startService.getView(t.getId());
        if ("进行中".equals(status)) {
            // 开赛先成功：第 5 洞虽然后来改维护，本轮冻结洞集仍含 5
            assertThat(outcomes).contains("started", "maintained");
            assertThat(finalView.phase()).isEqualTo(StartCheckView.PHASE_STARTED);
            assertThat(finalView.eligibleHoleNos()).contains(5);
        } else {
            // 维护先提交：开赛按最新球洞版本被阻止，赛事仍报名中，第 5 洞状态为维护
            assertThat(status).isEqualTo("报名中");
            assertThat(outcomes).contains("blocked", "maintained");
            assertThat(finalView.phase()).isEqualTo(StartCheckView.PHASE_BLOCKED);
            assertThat(finalView.blockedHoles())
                    .anyMatch(b -> b.holeNo() == 5 && "维护".equals(b.currentStatus()));
        }
    }

    // ---------------- 9. HTTP：GET 视图、开赛 400 带 data、409 带当前状态 ----------------

    @Test
    void http_getView_andStartBlocked400CarriesView_andStale409CarriesState() throws Exception {
        allOpen();
        Tournament ready = tournament("T-H1", "接口可开赛", true);
        ResponseEntity<String> ok = rest.getForEntity(
                "/api/tournaments/" + ready.getId() + "/start-check", String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode okBody = objectMapper.readTree(ok.getBody());
        assertThat(okBody.path("data").path("phase").asText()).isEqualTo("可开赛");

        Tournament blocked = tournament("T-H2", "接口阻断", true);
        Hole h4 = holeRepository.findByCourseIdAndHoleNo(courseId, 4).orElseThrow();
        h4.setStatus("封闭");
        holeRepository.save(h4);
        ResponseEntity<String> bad = rest.exchange(
                "/api/tournaments/" + blocked.getId() + "/start",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(new byte[0]), String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode badBody = objectMapper.readTree(bad.getBody());
        assertThat(badBody.path("ok").asBoolean()).isFalse();
        assertThat(badBody.path("message").asText()).contains("开赛校验未通过");
        JsonNode data = badBody.path("data");
        assertThat(data.path("phase").asText()).isEqualTo("被阻止");
        assertThat(data.path("blockedHoles")).hasSize(1);
        assertThat(data.path("blockedHoles").get(0).path("holeNo").asInt()).isEqualTo(4);
        assertThat(data.path("blockedHoles").get(0).path("currentStatus").asText()).isEqualTo("封闭");
        assertThat(data.path("tournamentStatus").asText()).isEqualTo("报名中");

        // 别人已开赛，再用旧版本调 start → 409，data 为最新「已开始」状态
        startService.start(blocked.getId(), null);
        long stale = tournamentRepository.findById(blocked.getId()).orElseThrow().getVersion() - 1;
        ResponseEntity<String> conflict = rest.exchange(
                "/api/tournaments/" + blocked.getId() + "/start?expectedVersion=" + stale,
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(new byte[0]), String.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode conflictBody = objectMapper.readTree(conflict.getBody());
        assertThat(conflictBody.path("data").path("phase").asText()).isEqualTo("已开始");
        assertThat(conflictBody.path("data").path("tournamentStatus").asText()).isEqualTo("进行中");
    }

    // ---------------- 10. advance 走开赛校验：报名中 → 进行中返回已开始视图 ----------------

    @Test
    void advanceToInProgress_runsStartCheck_andBlocksThenPasses() {
        allOpen();
        Tournament t = tournament("T-A2", "推进开赛", true);
        Hole h2 = holeRepository.findByCourseIdAndHoleNo(courseId, 2).orElseThrow();
        h2.setStatus("维护");
        holeRepository.save(h2);

        assertThatThrownBy(() -> tournamentService.advance(t.getId(), "进行中", null))
                .isInstanceOf(StartBlockedException.class);
        assertThat(tournamentRepository.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo("报名中");

        holeService.updateStatus(h2.getId(), "开放", null);
        Object result = tournamentService.advance(t.getId(), "进行中", null);
        assertThat(result).isInstanceOf(StartCheckView.class);
        assertThat(((StartCheckView) result).phase()).isEqualTo(StartCheckView.PHASE_STARTED);
        assertThat(tournamentRepository.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo("进行中");
    }
}
