package com.golf.course.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.golf.course.common.BizException;
import com.golf.course.common.ConflictException;
import com.golf.course.common.Dict;
import com.golf.course.common.StartBlockedException;
import com.golf.course.dto.StartCheckView;
import com.golf.course.entity.Course;
import com.golf.course.entity.Hole;
import com.golf.course.entity.Tournament;
import com.golf.course.entity.TournamentRoundHole;
import com.golf.course.entity.TournamentStartAttempt;
import com.golf.course.repository.CourseRepository;
import com.golf.course.repository.HoleRepository;
import com.golf.course.repository.TournamentRepository;
import com.golf.course.repository.TournamentRoundHoleRepository;
import com.golf.course.repository.TournamentStartAttemptRepository;

/**
 * 赛事开赛（报名中 → 进行中）可用性校验与编排，是本功能不可绕过的统一落点。
 *
 * <p>四条不可绕过的规则：</p>
 * <ol>
 *   <li><b>逐洞校验</b>——开赛前必须按赛事绑定球场的应配置洞数读取 <b>全部应打球洞</b>，
 *       只有「状态开放且标准杆 3/4/5、码距 1~800 均合法」的洞允许进入开赛；
 *       维护 / 封闭、未配置（应打但未建档）、基础数据不完整都阻止推进，
 *       并在接口 / 页面逐条列出受影响洞号、当前状态与修复提示。</li>
 *   <li><b>原子翻转、失败可恢复</b>——校验与「冻结本轮应打洞 + 翻状态为进行中」在同一个
 *       悲观锁事务内完成；校验失败整体回滚，赛事仍为报名中，<b>不清报名名单、不清成绩草稿、
 *       不留半个进行中状态</b>。每次尝试只往审计流水追加一行，不产生其他副作用。</li>
 *   <li><b>永远读最新版本</b>——查询校验结果与开赛重试都实时读取最新球洞版本，
 *       绝不复用任何一次失败的旧结果；管理员修复球洞后重新尝试即按最新数据判定。
 *       页面回传赛事版本 + 球洞版本指纹，期间球洞被他人维护时指纹变化 → 409「数据已变化」。</li>
 *   <li><b>开赛后冻结</b>——开赛成功瞬间把应打洞的洞号 / par / 码距 / 洞版本冻结到
 *       {@link TournamentRoundHole}；已进入进行中的赛事不会因为后来把球洞改为维护 / 封闭
 *       而静默改变本轮状态；但后续新赛事开赛必须重新做完整校验。</li>
 * </ol>
 */
@Service
public class TournamentStartService {

    private final TournamentRepository tournamentRepository;
    private final CourseRepository courseRepository;
    private final HoleRepository holeRepository;
    private final TournamentRoundHoleRepository roundHoleRepository;
    private final TournamentStartAttemptRepository attemptRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public TournamentStartService(TournamentRepository tournamentRepository,
                                  CourseRepository courseRepository,
                                  HoleRepository holeRepository,
                                  TournamentRoundHoleRepository roundHoleRepository,
                                  TournamentStartAttemptRepository attemptRepository,
                                  ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager) {
        this.tournamentRepository = tournamentRepository;
        this.courseRepository = courseRepository;
        this.holeRepository = holeRepository;
        this.roundHoleRepository = roundHoleRepository;
        this.attemptRepository = attemptRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ================================================================
    // 查询：实时校验（绝不缓存、绝不复用旧的失败结果）
    // ================================================================

    /**
     * 实时读取赛事与绑定球场的最新球洞版本，组装开赛可用性校验视图。
     * 报名中 → 可开赛 / 已阻止；进行中 / 已结束 → 已开始（回显冻结洞数与赛事版本）。
     */
    @Transactional(readOnly = true)
    public StartCheckView check(Long tournamentId) {
        Tournament t = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BizException("赛事不存在：" + tournamentId));
        return assemble(t, false);
    }

    // ================================================================
    // 开赛：悲观锁事务内「校验最新洞版本 → 冻结本轮球洞 → 翻状态」原子完成
    // ================================================================

    /**
     * 尝试开赛（外层不加事务，便于失败回滚后用独立事务写审计流水）。
     *
     * @param expectedVersion     页面看到的赛事版本号；过期 → 409
     * @param expectedHoleVersion 页面看到的球洞版本指纹；期间任一洞被维护 → 409
     */
    public Tournament start(Long tournamentId, Long expectedVersion, String expectedHoleVersion) {
        Tournament preview = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BizException("赛事不存在：" + tournamentId));
        // 快速失败：锁外先挡掉明显过期的请求，消息带当前最新赛事 / 球洞状态。
        if (expectedVersion != null && !expectedVersion.equals(preview.getVersion())) {
            StartCheckView current = check(tournamentId);
            ConflictException ce = new ConflictException("赛事数据已变化：你打开的是 v"
                    + expectedVersion + "，当前已是 v" + current.tournamentVersion()
                    + "。本次开赛未生效，请重新读取当前赛事与球洞状态后再操作", current);
            recordAttempt(preview, TournamentStartAttempt.RESULT_CONFLICT, expectedVersion,
                    expectedHoleVersion, null, ce.getMessage());
            throw ce;
        }

        try {
            return transactionTemplate.execute(status -> doStart(tournamentId, expectedVersion, expectedHoleVersion));
        } catch (StartBlockedException e) {
            // 开赛事务已随异常回滚（赛事仍报名中、无冻结洞行）；审计流水独立事务留痕后原样抛出。
            recordAttempt(preview, TournamentStartAttempt.RESULT_BLOCKED, expectedVersion,
                    expectedHoleVersion, e.checkView(), e.getMessage());
            throw e;
        } catch (ConflictException e) {
            recordAttempt(preview, TournamentStartAttempt.RESULT_CONFLICT, expectedVersion,
                    expectedHoleVersion,
                    (e.getData() instanceof StartCheckView cv) ? cv : null, e.getMessage());
            throw e;
        }
    }

    /** 悲观锁事务：赛事行 + 应打球洞行双锁定，校验通过才冻结球洞并翻状态，一次提交。 */
    private Tournament doStart(Long tournamentId, Long expectedVersion, String expectedHoleVersion) {
        Tournament locked = tournamentRepository.findWithLockById(tournamentId)
                .orElseThrow(() -> new BizException("赛事不存在：" + tournamentId));

        if ("已结束".equals(locked.getStatus())) {
            throw new ConflictException("赛事「" + locked.getName() + "」已经结束，开赛操作过期，"
                    + "请重新读取当前状态后再操作", assemble(locked, true));
        }
        if ("进行中".equals(locked.getStatus())) {
            // 多人同时点「开始」：第一个提交者已把赛事推进到进行中，后到者一律 409。
            throw new ConflictException("赛事「" + locked.getName() + "」已被其他人开始（当前为进行中），"
                    + "本次开赛未生效，请重新读取当前赛事与球洞状态", assemble(locked, true));
        }
        if (expectedVersion != null && !expectedVersion.equals(locked.getVersion())) {
            throw new ConflictException("赛事数据已变化：你打开的是 v" + expectedVersion
                    + "，当前已是 v" + locked.getVersion()
                    + "。本次开赛未生效，请重新读取当前赛事与球洞状态后再操作", assemble(locked, true));
        }

        Evaluation eval = evaluate(locked, true);
        if (expectedHoleVersion != null && !expectedHoleVersion.isBlank()
                && !expectedHoleVersion.equals(eval.holeVersionToken())) {
            // 页面打开后有人维护过球洞：必须基于最新洞版本重新确认，不能用旧结果开赛。
            throw new ConflictException("球洞数据已变化：你打开页面后球洞被维护过（洞版本 "
                    + expectedHoleVersion + " → " + eval.holeVersionToken()
                    + "），本次开赛未生效。请重新查看各洞最新状态后再开始", eval.toView(locked));
        }
        if (!eval.blocked().isEmpty()) {
            // 校验失败：抛出后事务整体回滚——不冻结洞、不翻状态，报名名单与成绩草稿原封不动。
            throw new StartBlockedException(eval.toView(locked));
        }

        // 全部应打球洞可用：同一事务内冻结本轮球洞并翻状态（任一步失败整体回滚）。
        LocalDateTime frozenAt = LocalDateTime.now();
        for (Hole hole : eval.readyHoles()) {
            TournamentRoundHole rh = new TournamentRoundHole();
            rh.setTournament(locked);
            rh.setHole(hole);
            rh.setHoleNo(hole.getHoleNo());
            rh.setPar(hole.getPar());
            rh.setYardage(hole.getYardage());
            rh.setHoleVersion(hole.getVersion());
            rh.setFrozenAt(frozenAt);
            roundHoleRepository.save(rh);
        }
        locked.setStatus("进行中");
        Tournament saved = tournamentRepository.saveAndFlush(locked);

        // 审计成功流水（与开赛同一事务提交：赛事开赛与留痕同生共死）。
        TournamentStartAttempt attempt = baseAttempt(saved, TournamentStartAttempt.RESULT_SUCCESS,
                expectedVersion, expectedHoleVersion, eval.holeVersionToken());
        attempt.setMessage("全部 " + eval.readyHoles().size() + " 个应打球洞校验通过，本轮球洞已冻结");
        attemptRepository.save(attempt);
        return saved;
    }

    // ================================================================
    // 核心校验：按绑定球场读取全部应打球洞，逐洞判定
    // ================================================================

    /**
     * @param lockHoles true 时对球场每个已配置洞加悲观写锁（开赛事务内，防止校验与维护并发）；
     *                  查询校验不加锁。
     */
    private Evaluation evaluate(Tournament t, boolean lockHoles) {
        List<StartCheckView.BlockedHole> blocked = new ArrayList<>();
        List<Hole> ready = new ArrayList<>();

        Course course = t.getCourse() == null ? null
                : courseRepository.findById(t.getCourse().getId()).orElse(null);
        if (course == null) {
            // 未绑定球场 / 绑定球场已被删除：基础数据不完整，无法界定应打洞集合。
            blocked.add(new StartCheckView.BlockedHole(null, "未配置", null, null,
                    "赛事未绑定球场，无法读取应打球洞",
                    "请先在赛事编辑中绑定开赛球场，再重新校验开赛"));
            return new Evaluation(null, null, blocked, ready, "");
        }
        if (!Dict.isValidCourseHoleCount(course.getHoleCount())) {
            blocked.add(new StartCheckView.BlockedHole(null, "数据异常", null, null,
                    "球场「" + course.getName() + "」应配置洞数不合法：" + course.getHoleCount(),
                    "请把球场应配置洞数修正为 " + Dict.COURSE_HOLE_MIN + " ~ " + Dict.COURSE_HOLE_MAX));
            return new Evaluation(course, course.getHoleCount(), blocked, ready, "");
        }

        // 读取该球场全部已配置洞（最新版本），按洞号映射；开赛事务内逐洞加行锁。
        List<Hole> configured = lockHoles
                ? courseLockedHoles(course.getId())
                : holeRepository.findByCourseIdOrderByHoleNoAsc(course.getId());
        Map<Integer, Hole> byNo = configured.stream()
                .collect(Collectors.toMap(Hole::getHoleNo, Function.identity(), (a, b) -> a));

        for (int no = 1; no <= course.getHoleCount(); no++) {
            Hole hole = byNo.get(no);
            if (hole == null) {
                blocked.add(new StartCheckView.BlockedHole(no, "未配置", null, null,
                        "第 " + no + " 洞应打但尚未建档",
                        "请到球洞管理为球场「" + course.getName() + "」新建第 " + no
                                + " 洞（标准杆 3/4/5、码距 > 0）并置为开放"));
                continue;
            }
            List<String> reasons = new ArrayList<>();
            List<String> fixes = new ArrayList<>();
            if (!"开放".equals(hole.getStatus())) {
                reasons.add("当前状态为「" + hole.getStatus() + "」，非开放洞");
                if ("维护".equals(hole.getStatus())) {
                    fixes.add("养护完成后把第 " + no + " 洞状态改回「开放」");
                } else {
                    fixes.add("确认第 " + no + " 洞可打后把状态改回「开放」");
                }
            }
            if (!Dict.isValidPar(hole.getPar())) {
                reasons.add("标准杆不合法（" + hole.getPar() + "，须为 3 / 4 / 5）");
                fixes.add("补全第 " + no + " 洞标准杆为 3、4 或 5");
            }
            if (!Dict.isValidYardage(hole.getYardage())) {
                reasons.add("码距不合法（" + hole.getYardage() + "，须为 "
                        + Dict.YARDAGE_MIN + " ~ " + Dict.YARDAGE_MAX + "）");
                fixes.add("补全第 " + no + " 洞码距（" + Dict.YARDAGE_MIN + " ~ " + Dict.YARDAGE_MAX + " 码）");
            }
            if (reasons.isEmpty()) {
                ready.add(hole);
            } else {
                String status = Dict.isValidHoleStatus(hole.getStatus())
                        && !"开放".equals(hole.getStatus()) ? hole.getStatus() : "数据不完整";
                blocked.add(new StartCheckView.BlockedHole(no, status, hole.getPar(), hole.getYardage(),
                        String.join("；", reasons), String.join("；", fixes)));
            }
        }
        ready.sort(Comparator.comparing(Hole::getHoleNo));
        return new Evaluation(course, course.getHoleCount(), blocked, ready, holeVersionToken(configured));
    }

    /** 开赛事务内对球场已配置洞逐洞加悲观写锁（按洞号顺序加锁，避免死锁）。 */
    private List<Hole> courseLockedHoles(Long courseId) {
        return holeRepository.findByCourseIdOrderByHoleNoAsc(courseId).stream()
                .map(h -> holeRepository.findWithLockById(h.getId()).orElseThrow())
                .sorted(Comparator.comparing(Hole::getHoleNo))
                .toList();
    }

    /**
     * 球洞版本指纹：球场全部已配置洞的 id:version 排序后做 FNV-1a 64 位散列。
     * 任一洞被维护（版本自增）或新增 / 删除洞都会使指纹变化，页面据此识别「数据已变化」。
     */
    static String holeVersionToken(List<Hole> holes) {
        String basis = holes.stream()
                .sorted(Comparator.comparing(Hole::getId))
                .map(h -> h.getId() + ":" + h.getVersion())
                .collect(Collectors.joining(","));
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < basis.length(); i++) {
            hash ^= basis.charAt(i);
            hash *= 0x100000001b3L;
        }
        return Long.toHexString(hash);
    }

    private StartCheckView assemble(Tournament t, boolean lockedContext) {
        if ("进行中".equals(t.getStatus()) || "已结束".equals(t.getStatus())) {
            // 已开赛：本轮应打洞以冻结清单为准；后来的洞状态变化不影响本轮。
            List<TournamentRoundHole> frozen = roundHoleRepository.findByTournamentIdOrderByHoleNoAsc(t.getId());
            List<Integer> frozenNos = frozen.stream().map(TournamentRoundHole::getHoleNo).toList();
            Course c = t.getCourse() == null ? null
                    : courseRepository.findById(t.getCourse().getId()).orElse(null);
            String token = "";
            if (c != null) {
                token = holeVersionToken(holeRepository.findByCourseIdOrderByHoleNoAsc(c.getId()));
            }
            return new StartCheckView(StartCheckView.STATE_STARTED, t.getId(), t.getName(),
                    t.getTournamentNo(), t.getStatus(), t.getVersion(),
                    c == null ? null : c.getId(), c == null ? null : c.getName(),
                    frozen.size(), token, LocalDateTime.now(), List.of(), frozenNos, false);
        }
        return evaluate(t, lockedContext).toView(t);
    }

    // ================================================================
    // 审计流水（独立事务，失败 / 冲突也要留痕）
    // ================================================================

    private void recordAttempt(Tournament preview, String result, Long expectedVersion,
                               String expectedHoleVersion, StartCheckView current, String message) {
        transactionTemplate.executeWithoutResult(status -> {
            Tournament t = tournamentRepository.findById(preview.getId()).orElse(preview);
            TournamentStartAttempt a = baseAttempt(t, result, expectedVersion, expectedHoleVersion,
                    current == null ? null : current.holeVersionToken());
            a.setMessage(message == null || message.length() <= 512 ? message : message.substring(0, 512));
            if (current != null && result.equals(TournamentStartAttempt.RESULT_BLOCKED)) {
                a.setBlockedJson(toBlockedJson(current.blockedHoles()));
            }
            attemptRepository.save(a);
        });
    }

    private TournamentStartAttempt baseAttempt(Tournament t, String result, Long expectedVersion,
                                               String expectedHoleVersion, String currentHoleVersion) {
        TournamentStartAttempt a = new TournamentStartAttempt();
        a.setTournament(t);
        a.setResult(result);
        a.setExpectedTournamentVersion(expectedVersion);
        a.setExpectedHoleVersion(expectedHoleVersion);
        a.setCurrentTournamentVersion(t.getVersion());
        a.setCurrentHoleVersion(currentHoleVersion);
        a.setCreatedAt(LocalDateTime.now());
        return a;
    }

    private String toBlockedJson(List<StartCheckView.BlockedHole> blocked) {
        try {
            return objectMapper.writeValueAsString(blocked);
        } catch (JsonProcessingException e) {
            throw new BizException("开赛阻止明细序列化失败：" + e.getMessage());
        }
    }

    /** 校验中间结果。 */
    private final class Evaluation {
        private final Course course;
        private final Integer expectedHoleCount;
        private final List<StartCheckView.BlockedHole> blocked;
        private final List<Hole> readyHoles;
        private final String holeVersionToken;

        Evaluation(Course course, Integer expectedHoleCount, List<StartCheckView.BlockedHole> blocked,
                   List<Hole> readyHoles, String holeVersionToken) {
            this.course = course;
            this.expectedHoleCount = expectedHoleCount;
            this.blocked = blocked;
            this.readyHoles = readyHoles;
            this.holeVersionToken = holeVersionToken;
        }

        String holeVersionToken() {
            return holeVersionToken;
        }

        List<StartCheckView.BlockedHole> blocked() {
            return blocked;
        }

        List<Hole> readyHoles() {
            return readyHoles;
        }

        StartCheckView toView(Tournament t) {
            List<Integer> readyNos = readyHoles.stream().map(Hole::getHoleNo).toList();
            String state = blocked.isEmpty() ? StartCheckView.STATE_READY : StartCheckView.STATE_BLOCKED;
            return new StartCheckView(state, t.getId(), t.getName(), t.getTournamentNo(), t.getStatus(),
                    t.getVersion(),
                    Optional.ofNullable(course).map(Course::getId).orElse(null),
                    Optional.ofNullable(course).map(Course::getName).orElse(null),
                    expectedHoleCount, holeVersionToken, LocalDateTime.now(),
                    List.copyOf(blocked), readyNos, !blocked.isEmpty());
        }
    }
}
