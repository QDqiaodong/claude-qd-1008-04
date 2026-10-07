package com.golf.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
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
import com.golf.course.dto.StartCheckView;
import com.golf.course.entity.Course;
import com.golf.course.entity.Hole;
import com.golf.course.entity.Player;
import com.golf.course.entity.Tournament;
import com.golf.course.entity.TournamentEntry;
import com.golf.course.entity.TournamentStartAttempt;
import com.golf.course.repository.CourseRepository;
import com.golf.course.repository.HoleRepository;
import com.golf.course.repository.PlayerRepository;
import com.golf.course.repository.TournamentEntryRepository;
import com.golf.course.repository.TournamentRepository;
import com.golf.course.repository.TournamentRoundHoleRepository;
import com.golf.course.repository.TournamentStartAttemptRepository;

/**
 * 赛事开赛前球场可用性校验集成测试（H2 MySQL 模式）：
 * 逐洞阻止 / 失败恢复（名单与草稿保留、无半进行中）/ 重试读最新版本 /
 * 并发开赛唯一成功 / 版本过期 409 / 开赛后改洞不影响本轮、新赛事重新校验 / HTTP 链路。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TournamentStartCheckIntegrationTest {

    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private HoleRepository holeRepository;
    @Autowired private PlayerRepository playerRepository;
    @Autowired private TournamentEntryRepository entryRepository;
    @Autowired private TournamentRoundHoleRepository roundHoleRepository;
    @Autowired private TournamentStartAttemptRepository attemptRepository;
    @Autowired private TournamentStartService startService;
    @Autowired private HoleService holeService;
    @Autowired private ScorecardService scorecardService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM handicap_history_round");
        jdbc.execute("DELETE FROM handicap_history");
        jdbc.execute("DELETE FROM handicap_run_error");
        jdbc.execute("DELETE FROM handicap_run");
        jdbc.execute("DELETE FROM handicap_rule");
        jdbc.execute("DELETE FROM tournament_start_attempt");
        jdbc.execute("DELETE FROM tournament_round_hole");
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
        jdbc.execute("DELETE FROM course");
    }

    private Course allOpenCourse(String no, int n) {
        Course c = new Course();
        c.setCourseNo(no);
        c.setName("球场" + no);
        c.setHoleCount(n);
        c.setCreatedAt(LocalDateTime.now());
        courseRepository.save(c);
        for (int i = 1; i <= n; i++) {
            Hole h = new Hole();
            h.setCourse(c);
            h.setHoleNo(i);
            h.setPar(i % 2 == 0 ? 4 : 3);
            h.setYardage(300 + i * 13);
            h.setStatus("开放");
            h.setCreatedAt(LocalDateTime.now());
            holeRepository.save(h);
        }
        return c;
    }

    private Tournament mkTournament(String no, String name, Course course) {
        Tournament t = new Tournament();
        t.setTournamentNo(no);
        t.setName(name);
        t.setStartDate(LocalDate.of(2026, 10, 10));
        t.setEndDate(LocalDate.of(2026, 10, 12));
        t.setStatus("报名中");
        t.setLocation(name);
        t.setCourse(course);
        t.setCreatedAt(LocalDateTime.now());
        return tournamentRepository.save(t);
    }

    private Player mkPlayer(String no) {
        Player p = new Player();
        p.setPlayerNo(no);
        p.setName("球手" + no);
        p.setHandicap(12);
        p.setCreatedAt(LocalDateTime.now());
        return playerRepository.save(p);
    }

    // ---------------- 1. 全部开放且数据合法 → 可开赛 → 开赛成功并冻结 ----------------

    @Test
    void allHolesOpenAndValid_checkReady_startFreezesRound() {
        Course c = allOpenCourse("C-OK", 9);
        Tournament t = mkTournament("T-OK1", "就绪赛事", c);

        StartCheckView check = startService.check(t.getId());
        assertThat(check.state()).isEqualTo(StartCheckView.STATE_READY);
        assertThat(check.blocking()).isFalse();
        assertThat(check.blockedHoles()).isEmpty();
        assertThat(check.readyHoleNos()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(check.holeVersionToken()).isNotBlank();

        Tournament started = startService.start(t.getId(), t.getVersion(), check.holeVersionToken());
        assertThat(started.getStatus()).isEqualTo("进行中");
        assertThat(roundHoleRepository.findByTournamentIdOrderByHoleNoAsc(t.getId())).hasSize(9);

        StartCheckView after = startService.check(t.getId());
        assertThat(after.state()).isEqualTo(StartCheckView.STATE_STARTED);
        assertThat(after.readyHoleNos()).hasSize(9);

        List<TournamentStartAttempt> attempts = attemptRepository.findByTournamentIdOrderByIdDesc(t.getId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getResult()).isEqualTo(TournamentStartAttempt.RESULT_SUCCESS);
    }

    // ---------------- 2. 维护 / 封闭洞阻止开赛，明细含洞号 / 状态 / 修复提示 ----------------

    @Test
    void maintainedAndClosedHoles_blockStart_withPerHoleDetails() {
        Course c = allOpenCourse("C-M1", 5);
        holeService.updateStatus(holeRepository.findByCourseIdAndHoleNo(c.getId(), 2).orElseThrow().getId(),
                "维护", null);
        holeService.updateStatus(holeRepository.findByCourseIdAndHoleNo(c.getId(), 5).orElseThrow().getId(),
                "封闭", null);
        Tournament t = mkTournament("T-M1", "被阻赛事", c);
        Player p = mkPlayer("PM1");
        TournamentEntry e = new TournamentEntry();
        e.setTournament(t); e.setPlayer(p); e.setRoundCount(1); e.setCreatedAt(LocalDateTime.now());
        entryRepository.save(e);
        // 成绩草稿（记分卡草稿）也先建好，失败后必须保留
        scorecardService.getOrCreateDraft(t.getId(), p.getId(), 1);

        StartCheckView check = startService.check(t.getId());
        assertThat(check.state()).isEqualTo(StartCheckView.STATE_BLOCKED);
        assertThat(check.blockedHoles()).hasSize(2);
        assertThat(check.blockedHoles()).filteredOn(b -> b.holeNo() == 2).singleElement().satisfies(b -> {
            assertThat(b.status()).isEqualTo("维护");
            assertThat(b.fixHint()).contains("开放");
        });
        assertThat(check.blockedHoles()).filteredOn(b -> b.holeNo() == 5).singleElement()
                .extracting(StartCheckView.BlockedHole::status).isEqualTo("封闭");

        assertThatThrownBy(() -> startService.start(t.getId(), t.getVersion(), check.holeVersionToken()))
                .isInstanceOfSatisfying(StartBlockedException.class,
                        ex -> assertThat(ex.checkView().blockedHoles()).hasSize(2));

        // 失败恢复：赛事仍报名中、无冻结洞（不留半个进行中）、名单与草稿保留
        assertThat(tournamentRepository.findById(t.getId()).orElseThrow().getStatus()).isEqualTo("报名中");
        assertThat(roundHoleRepository.findByTournamentIdOrderByHoleNoAsc(t.getId())).isEmpty();
        assertThat(entryRepository.findByTournamentIdOrderByIdAsc(t.getId())).hasSize(1);
        assertThat(scorecardService.listByTournament(t.getId())).hasSize(1);
        assertThat(attemptRepository.findByTournamentIdOrderByIdDesc(t.getId()))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.getResult()).isEqualTo(TournamentStartAttempt.RESULT_BLOCKED);
                    assertThat(a.getBlockedJson()).contains("维护");
                });
    }

    // ---------------- 3. 未配置洞（应打但未建档）阻止开赛 ----------------

    @Test
    void missingHoleConfiguration_blocksAsUnconfigured() {
        Course c = allOpenCourse("C-M2", 8); // 只建了 8 个洞
        jdbc.update("UPDATE course SET hole_count = 9 WHERE id = ?", c.getId());
        c = courseRepository.findById(c.getId()).orElseThrow();
        Tournament t = mkTournament("T-M2", "缺口赛事", c);

        StartCheckView check = startService.check(t.getId());
        assertThat(check.state()).isEqualTo(StartCheckView.STATE_BLOCKED);
        assertThat(check.blockedHoles()).singleElement().satisfies(b -> {
            assertThat(b.holeNo()).isEqualTo(9);
            assertThat(b.status()).isEqualTo("未配置");
            assertThat(b.reason()).contains("尚未建档");
            assertThat(b.fixHint()).contains("新建第 9 洞");
        });
        assertThatThrownBy(() -> startService.start(t.getId(), null, null))
                .isInstanceOf(StartBlockedException.class);
    }

    // ---------------- 4. 基础数据不完整（par / 码距非法）阻止开赛 ----------------

    @Test
    void incompleteBaselineData_blocksStart() {
        Course c = allOpenCourse("C-M3", 4);
        // 绕过维护接口直接写入脏基础数据（模拟历史脏数据 / 外部改动）
        Hole badPar = holeRepository.findByCourseIdAndHoleNo(c.getId(), 2).orElseThrow();
        badPar.setPar(7);
        holeRepository.saveAndFlush(badPar);
        Hole badYard = holeRepository.findByCourseIdAndHoleNo(c.getId(), 4).orElseThrow();
        badYard.setYardage(0);
        holeRepository.saveAndFlush(badYard);
        Tournament t = mkTournament("T-M3", "脏数据赛事", c);

        StartCheckView check = startService.check(t.getId());
        assertThat(check.blockedHoles()).hasSize(2);
        assertThat(check.blockedHoles()).filteredOn(b -> b.holeNo() == 2).singleElement().satisfies(b -> {
            assertThat(b.status()).isEqualTo("数据不完整");
            assertThat(b.reason()).contains("标准杆不合法");
            assertThat(b.fixHint()).contains("3、4 或 5");
        });
        assertThat(check.blockedHoles()).filteredOn(b -> b.holeNo() == 4).singleElement()
                .extracting(StartCheckView.BlockedHole::reason).asString().contains("码距不合法");
        assertThatThrownBy(() -> startService.start(t.getId(), null, null))
                .isInstanceOf(StartBlockedException.class);
        assertThat(tournamentRepository.findById(t.getId()).orElseThrow().getStatus()).isEqualTo("报名中");
    }

    // ---------------- 5. 修复后重试读最新版本成功（不复用第一次失败结果） ----------------

    @Test
    void retryAfterFix_readsLatestHoleVersion_andSucceeds() {
        Course c = allOpenCourse("C-R1", 3);
        Hole h2 = holeRepository.findByCourseIdAndHoleNo(c.getId(), 2).orElseThrow();
        holeService.updateStatus(h2.getId(), "维护", null);
        Tournament t = mkTournament("T-R1", "重试赛事", c);

        StartCheckView blocked = startService.check(t.getId());
        assertThat(blocked.state()).isEqualTo(StartCheckView.STATE_BLOCKED);
        String tokenWhenBlocked = blocked.holeVersionToken();
        assertThatThrownBy(() -> startService.start(t.getId(), null, null))
                .isInstanceOf(StartBlockedException.class);

        // 管理员修复：第 2 洞改回开放（洞版本自增，版本指纹随之变化）
        holeService.updateStatus(h2.getId(), "开放", null);

        StartCheckView rechecked = startService.check(t.getId());
        assertThat(rechecked.state()).isEqualTo(StartCheckView.STATE_READY);
        assertThat(rechecked.holeVersionToken()).isNotEqualTo(tokenWhenBlocked);

        Tournament started = startService.start(t.getId(), null, null);
        assertThat(started.getStatus()).isEqualTo("进行中");
        assertThat(roundHoleRepository.findByTournamentIdOrderByHoleNoAsc(t.getId())).hasSize(3);
        // 审计流水：先阻止、后开赛，各一行
        List<TournamentStartAttempt> attempts = attemptRepository.findByTournamentIdOrderByIdDesc(t.getId());
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0).getResult()).isEqualTo(TournamentStartAttempt.RESULT_SUCCESS);
        assertThat(attempts.get(1).getResult()).isEqualTo(TournamentStartAttempt.RESULT_BLOCKED);
    }

    // ---------------- 6. 多人同时开始：只允许一个成功，其余 409 ----------------

    @Test
    void concurrentStart_onlyOneSucceeds_losersConflictAndNoDuplicateFrozenRows() throws Exception {
        Course c = allOpenCourse("C-C1", 9);
        Tournament t = mkTournament("T-C1", "并发开赛", c);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    startLatch.await();
                    startService.start(t.getId(), null, null);
                    success.incrementAndGet();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (ConflictException ce) {
                    conflict.incrementAndGet();
                } catch (RuntimeException re) {
                    errors.add(re);
                }
            }));
        }
        startLatch.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertThat(errors).as("unexpected: %s", errors).isEmpty();
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        assertThat(tournamentRepository.findById(t.getId()).orElseThrow().getStatus()).isEqualTo("进行中");
        assertThat(roundHoleRepository.findByTournamentIdOrderByHoleNoAsc(t.getId())).hasSize(9);
    }

    // ---------------- 7. 过期赛事版本 → 409 且带当前状态 ----------------

    @Test
    void staleTournamentVersion_returnsConflictWithCurrentState() {
        Course c = allOpenCourse("C-V1", 3);
        Tournament t = mkTournament("T-V1", "版本赛事", c);
        long stale = t.getVersion() - 1;
        assertThatThrownBy(() -> startService.start(t.getId(), stale, null))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.getMessage()).contains("数据已变化");
                    assertThat(ex.getData()).isInstanceOf(StartCheckView.class);
                    StartCheckView cv = (StartCheckView) ex.getData();
                    assertThat(cv.tournamentVersion()).isEqualTo(t.getVersion());
                    assertThat(cv.state()).isEqualTo(StartCheckView.STATE_READY);
                });
        assertThat(tournamentRepository.findById(t.getId()).orElseThrow().getStatus()).isEqualTo("报名中");
    }

    // ---------------- 8. 页面打开后球洞被维护（指纹过期）→ 409 ----------------

    @Test
    void holeChangedAfterPageLoad_tokenStale_returnsConflict() {
        Course c = allOpenCourse("C-V2", 4);
        Tournament t = mkTournament("T-V2", "洞变化赛事", c);
        StartCheckView view = startService.check(t.getId());
        String token = view.holeVersionToken();

        // 另一管理员把第 3 洞改为维护
        Hole h3 = holeRepository.findByCourseIdAndHoleNo(c.getId(), 3).orElseThrow();
        holeService.updateStatus(h3.getId(), "维护", null);

        assertThatThrownBy(() -> startService.start(t.getId(), t.getVersion(), token))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.getMessage()).contains("球洞数据已变化");
                    assertThat(ex.getData()).isInstanceOf(StartCheckView.class);
                    StartCheckView cv = (StartCheckView) ex.getData();
                    assertThat(cv.state()).isEqualTo(StartCheckView.STATE_BLOCKED);
                    assertThat(cv.blockedHoles()).singleElement().extracting(StartCheckView.BlockedHole::holeNo)
                            .isEqualTo(3);
                });
        // 冲突不推进状态
        assertThat(tournamentRepository.findById(t.getId()).orElseThrow().getStatus()).isEqualTo("报名中");
        assertThat(roundHoleRepository.findByTournamentIdOrderByHoleNoAsc(t.getId())).isEmpty();
    }

    // ---------------- 9. 开赛成功后改洞不影响本轮；新赛事重新校验被阻止 ----------------

    @Test
    void afterStarted_changingHoleDoesNotAffectRound_butNewTournamentRechecks() {
        Course c = allOpenCourse("C-F1", 5);
        Tournament t1 = mkTournament("T-F1", "已开赛赛事", c);
        startService.start(t1.getId(), null, null);
        assertThat(roundHoleRepository.findByTournamentIdOrderByHoleNoAsc(t1.getId())).hasSize(5);

        // 赛后把第 1、4 洞改为维护 / 封闭：本轮冻结清单不变
        Hole h1 = holeRepository.findByCourseIdAndHoleNo(c.getId(), 1).orElseThrow();
        Hole h4 = holeRepository.findByCourseIdAndHoleNo(c.getId(), 4).orElseThrow();
        holeService.updateStatus(h1.getId(), "维护", null);
        holeService.updateStatus(h4.getId(), "封闭", null);

        StartCheckView startedView = startService.check(t1.getId());
        assertThat(startedView.state()).isEqualTo(StartCheckView.STATE_STARTED);
        assertThat(startedView.readyHoleNos()).containsExactly(1, 2, 3, 4, 5);
        // 冻结清单里的洞号 / par / 码距原样保留
        assertThat(roundHoleRepository.findByTournamentIdOrderByHoleNoAsc(t1.getId()))
                .allSatisfy(rh -> assertThat(rh.getPar()).isIn(3, 4, 5));

        // 同球场的后续新赛事必须重新校验 → 被 1、4 洞阻止
        Tournament t2 = mkTournament("T-F2", "后续新赛事", c);
        StartCheckView newCheck = startService.check(t2.getId());
        assertThat(newCheck.state()).isEqualTo(StartCheckView.STATE_BLOCKED);
        assertThat(newCheck.blockedHoles()).extracting(StartCheckView.BlockedHole::holeNo)
                .containsExactlyInAnyOrder(1, 4);
        assertThatThrownBy(() -> startService.start(t2.getId(), null, null))
                .isInstanceOf(StartBlockedException.class);
        assertThat(tournamentRepository.findById(t2.getId()).orElseThrow().getStatus()).isEqualTo("报名中");
    }

    // ---------------- 10. 未绑定球场：基础数据不完整，阻止开赛 ----------------

    @Test
    void tournamentWithoutCourse_isBlocked() {
        Tournament t = mkTournament("T-NC", "无球场赛事", allOpenCourse("C-NC", 2));
        jdbc.update("UPDATE tournament SET course_id = NULL WHERE id = ?", t.getId());
        final Tournament tnc = tournamentRepository.findById(t.getId()).orElseThrow();

        StartCheckView check = startService.check(tnc.getId());
        assertThat(check.state()).isEqualTo(StartCheckView.STATE_BLOCKED);
        assertThat(check.blockedHoles()).singleElement().satisfies(b -> {
            assertThat(b.status()).isEqualTo("未配置");
            assertThat(b.reason()).contains("未绑定球场");
            assertThat(b.fixHint()).contains("绑定");
        });
        assertThatThrownBy(() -> startService.start(tnc.getId(), null, null))
                .isInstanceOf(StartBlockedException.class);
    }

    // ---------------- 11. HTTP 链路：start-check 200；开赛被阻止 400 带 data；冲突 409 带 data ----------------

    @Test
    void http_startCheckAndAdvance_returnStructuredBody() throws Exception {
        Course c = allOpenCourse("C-H1", 4);
        Hole h3 = holeRepository.findByCourseIdAndHoleNo(c.getId(), 3).orElseThrow();
        holeService.updateStatus(h3.getId(), "维护", null);
        Tournament t = mkTournament("T-H1", "接口开赛赛事", c);

        ResponseEntity<String> checkResp = rest.getForEntity(
                "/api/tournaments/" + t.getId() + "/start-check", String.class);
        assertThat(checkResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode checkBody = objectMapper.readTree(checkResp.getBody());
        assertThat(checkBody.path("data").path("state").asText()).isEqualTo("已阻止");
        assertThat(checkBody.path("data").path("blockedHoles")).hasSize(1);
        JsonNode blocked = checkBody.path("data").path("blockedHoles").get(0);
        assertThat(blocked.path("holeNo").asInt()).isEqualTo(3);
        assertThat(blocked.path("status").asText()).isEqualTo("维护");
        assertThat(blocked.path("fixHint").asText()).isNotBlank();

        ResponseEntity<String> advResp = rest.postForEntity(
                "/api/tournaments/" + t.getId() + "/advance?target=" + "进行中", null, String.class);
        assertThat(advResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode advBody = objectMapper.readTree(advResp.getBody());
        assertThat(advBody.path("ok").asBoolean()).isFalse();
        assertThat(advBody.path("data").path("state").asText()).isEqualTo("已阻止");
        assertThat(advBody.path("data").path("blockedHoles")).hasSize(1);

        // 修复后走 HTTP 开赛成功
        holeService.updateStatus(h3.getId(), "开放", null);
        ResponseEntity<String> okResp = rest.postForEntity(
                "/api/tournaments/" + t.getId() + "/advance?target=" + "进行中", null, String.class);
        assertThat(okResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode okBody = objectMapper.readTree(okResp.getBody());
        assertThat(okBody.path("data").path("status").asText()).isEqualTo("进行中");

        // 重复开始（已是进行中）→ 409
        ResponseEntity<String> dupResp = rest.postForEntity(
                "/api/tournaments/" + t.getId() + "/advance?target=" + "进行中", null, String.class);
        assertThat(dupResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode dupBody = objectMapper.readTree(dupResp.getBody());
        assertThat(dupBody.path("message").asText()).contains("已被其他人开始");
        assertThat(dupBody.path("data").path("state").asText()).isEqualTo("已开始");
    }

    // ---------------- 12. 两人同时维护同一洞：过期版本 409，不静默覆盖 ----------------

    @Test
    void concurrentHoleEdit_staleVersionRejected() {
        Course c = allOpenCourse("C-H2", 2);
        Hole h = holeRepository.findByCourseIdAndHoleNo(c.getId(), 1).orElseThrow();
        long v = h.getVersion();
        // 第一人先改为维护成功
        Hole updated = holeService.updateStatus(h.getId(), "维护", v);
        assertThat(updated.getVersion()).isGreaterThan(v);
        // 第二人拿着旧版本改为封闭 → 409，洞仍是维护
        assertThatThrownBy(() -> {
            Hole input = new Hole();
            input.setStatus("封闭");
            holeService.update(h.getId(), input, v);
        }).isInstanceOf(ConflictException.class).hasMessageContaining("球洞数据已变化");
        assertThat(holeRepository.findById(h.getId()).orElseThrow().getStatus()).isEqualTo("维护");
    }
}
