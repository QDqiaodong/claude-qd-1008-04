package com.golf.course.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import com.golf.course.common.Dict;
import com.golf.course.common.StartBlockedException;
import com.golf.course.common.StartConflictException;
import com.golf.course.dto.StartCheckView;
import com.golf.course.entity.Course;
import com.golf.course.entity.Hole;
import com.golf.course.entity.Tournament;
import com.golf.course.entity.TournamentStartCheck;
import com.golf.course.repository.CourseRepository;
import com.golf.course.repository.HoleRepository;
import com.golf.course.repository.TournamentRepository;
import com.golf.course.repository.TournamentStartCheckRepository;

/**
 * 赛事开赛前球场可用性校验：从「报名中」推进到「进行中」的唯一收口落点。
 *
 * <p>四条不可绕过的规则：</p>
 * <ol>
 *   <li><b>按最新版本全量校验</b>——开赛先锁赛事行（串行化并发开赛），然后重新查询该赛事
 *       绑定球场的全部球洞（不接收任何缓存 / 旧结果）。只有「开放 + par∈{3,4,5}
 *       + 码距合法」的洞才允许进入开赛；维护 / 封闭、未配置（应配未配的洞号）、
 *       基础数据不完整（未绑定球场、洞数非法、par/码距非法或状态值无法识别）一律阻止。
 *       每次尝试都在锁内重新读取最新球洞版本，绝不复用任何一次旧校验结果。</li>
 *   <li><b>失败原子恢复</b>——校验失败时开赛事务整体回滚：赛事仍「报名中」，
 *       报名名单（tournament_entry）与成绩 / 记分卡草稿（score / scorecard*）一概不动，
 *       绝不留下半个「进行中」状态；失败明细以独立事务留痕，修复后可直接重试。</li>
 *   <li><b>并发只允许一次成功</b>——多人同时点「开始」由赛事行悲观写锁串行；
 *       同时维护同一球洞由球洞 {@code @Version} 乐观锁兜底，赛事 {@code @Version}
 *       负责页面 expectedVersion 过期检测。过期操作收 409「数据已变化」并返回按当前
 *       赛事与球洞状态重算的视图；只有基于最新版本的一次成功。</li>
 *   <li><b>开赛后冻结</b>——开赛瞬间把应打洞集冻结到 {@link TournamentStartCheck}，
 *       之后球洞被改成维护 / 封闭，本轮排行榜 / 记分卡仍读冻结洞集，不静默改变本轮状态；
 *       后续新赛事没有冻结记录，必须重新校验。</li>
 * </ol>
 */
@Service
public class TournamentStartService {

    private final TournamentRepository tournamentRepository;
    private final CourseRepository courseRepository;
    private final HoleRepository holeRepository;
    private final TournamentStartCheckRepository checkRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public TournamentStartService(TournamentRepository tournamentRepository,
                                  CourseRepository courseRepository,
                                  HoleRepository holeRepository,
                                  TournamentStartCheckRepository checkRepository,
                                  ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager) {
        this.tournamentRepository = tournamentRepository;
        this.courseRepository = courseRepository;
        this.holeRepository = holeRepository;
        this.checkRepository = checkRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ================================================================
    // 查询：永远按最新球洞版本实时重算的开赛校验视图（不落任何痕迹）
    // ================================================================

    /**
     * 读取赛事开赛校验视图。
     * <ul>
     *   <li>已结束：页面不展示开赛面板（phase 标记为已开始口径）；</li>
     *   <li>进行中：已开始——应打洞集读开赛冻结记录，后续改洞不影响本轮；</li>
     *   <li>报名中：按此刻最新球洞实时评估 → 可开赛 / 被阻止（从未尝试也算，供页面预检）。</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public StartCheckView getView(Long tournamentId) {
        Tournament t = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BizException("赛事不存在：" + tournamentId));
        TournamentStartCheck persisted = checkRepository.findByTournamentId(tournamentId).orElse(null);
        if ("已结束".equals(t.getStatus()) || "进行中".equals(t.getStatus())) {
            return startedView(t, persisted);
        }
        Evaluation eval = evaluate(t);
        return assemble(t, eval, persisted);
    }

    // ================================================================
    // 开赛：报名中 → 进行中（原子、可回滚、并发安全、重试取最新版本）
    // ================================================================

    /**
     * 尝试开赛。外层不加事务（与排行榜收口同构）：
     * <ol>
     *   <li>独立事务：赛事行悲观写锁（串行化并发开赛）→ 重新查询最新球洞并校验
     *       → 通过则冻结洞集、翻「进行中」，一次提交；不通过整体回滚。</li>
     *   <li>校验失败：用第二个独立事务整行覆盖「被阻止」留痕（重试永远覆盖为最新结果），
     *       再抛 400 并携带最新视图。</li>
     * </ol>
     *
     * @param expectedVersion 页面加载时看到的赛事版本号；非空且不一致直接 409
     */
    public StartCheckView start(Long tournamentId, Long expectedVersion) {
        Tournament t = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BizException("赛事不存在：" + tournamentId));
        if (expectedVersion != null && !expectedVersion.equals(t.getVersion())) {
            throw new StartConflictException(staleMessage(expectedVersion, t.getVersion()),
                    getView(tournamentId));
        }
        try {
            // 锁内会再次按加锁后的最新版本复核 expectedVersion，杜绝「预检后、拿锁前」窗口
            return transactionTemplate.execute(status -> doStart(tournamentId, expectedVersion));
        } catch (StartBlockedException e) {
            // 开赛事务已随异常回滚（赛事仍报名中、名单与草稿未动）；失败明细独立事务留痕。
            recordBlocked(tournamentId, e.view());
            throw e;
        }
    }

    /** 在「赛事行悲观锁串行化 + 赛事 / 球洞乐观版本」保护下完成最新版本校验、冻结与状态推进。 */
    private StartCheckView doStart(Long tournamentId, Long expectedVersion) {
        Tournament locked = tournamentRepository.findWithLockById(tournamentId)
                .orElseThrow(() -> new BizException("赛事不存在：" + tournamentId));
        // 行锁内复核版本：预检通过到拿锁之间赛事可能已被他人开赛 / 维护
        if (expectedVersion != null && !expectedVersion.equals(locked.getVersion())) {
            throw new StartConflictException(staleMessage(expectedVersion, locked.getVersion()),
                    getView(tournamentId));
        }
        if ("进行中".equals(locked.getStatus()) || "已结束".equals(locked.getStatus())) {
            throw new StartConflictException("赛事「" + locked.getName() + "」已是「"
                    + locked.getStatus() + "」状态，本次开赛操作过期（他人可能已先开赛）；"
                    + "请重新读取当前赛事与球洞状态", getView(tournamentId));
        }
        if (!"报名中".equals(locked.getStatus())) {
            throw new BizException("只有「报名中」的赛事可以开赛，当前状态：" + locked.getStatus());
        }

        // 先锁洞再评估：锁后读到的就是最新已提交版本；评估本身不做任何写。
        Evaluation eval = evaluateLocked(locked);
        if (!eval.blockedHoles().isEmpty()) {
            TournamentStartCheck stale = checkRepository.findByTournamentId(tournamentId).orElse(null);
            throw new StartBlockedException("赛事「" + locked.getName()
                    + "」开赛校验未通过：" + eval.blockedHoles().size()
                    + " 个应打球洞不可用或基础数据不完整，已阻止开赛；赛事仍保持「报名中」，"
                    + "请按提示修复后重试（每次重试都会读取最新球洞版本）",
                    assemble(locked, eval, stale));
        }

        LocalDateTime now = LocalDateTime.now();
        TournamentStartCheck check = checkRepository.findByTournamentId(tournamentId)
                .orElseGet(TournamentStartCheck::new);
        check.setTournament(locked);
        check.setCheckType(TournamentStartCheck.TYPE_PASSED);
        check.setCourse(locked.getCourse());
        check.setEligibleHoleNos(eval.eligibleHoleNos().stream()
                .map(String::valueOf).collect(Collectors.joining(",")));
        check.setBlockedHolesJson(null);
        check.setCourseVersionHash(eval.versionHash());
        check.setCheckedAt(now);
        check.setUpdatedAt(now);
        checkRepository.saveAndFlush(check);

        locked.setStatus("进行中");
        tournamentRepository.saveAndFlush(locked);

        return assemble(locked, eval, check);
    }

    /** 开赛失败留痕：独立事务整行覆盖，保证重试不复用第一次失败的旧结果。 */
    private void recordBlocked(Long tournamentId, StartCheckView view) {
        transactionTemplate.executeWithoutResult(status -> {
            Tournament t = tournamentRepository.findById(tournamentId)
                    .orElseThrow(() -> new BizException("赛事不存在：" + tournamentId));
            TournamentStartCheck check = checkRepository.findByTournamentId(tournamentId)
                    .orElseGet(TournamentStartCheck::new);
            LocalDateTime now = LocalDateTime.now();
            check.setTournament(t);
            check.setCheckType(TournamentStartCheck.TYPE_BLOCKED);
            check.setCourse(t.getCourse());
            check.setEligibleHoleNos(null);
            check.setBlockedHolesJson(toJson(view.blockedHoles()));
            check.setCourseVersionHash(view.courseVersionHash());
            check.setCheckedAt(now);
            check.setUpdatedAt(now);
            checkRepository.save(check);
        });
    }

    // ================================================================
    // 核心评估：按赛事绑定球场读取全部应打球洞（只读预检 / 锁内开赛共用规则）
    // ================================================================

    /** 只读评估（GET 预检用，无事务；实时读最新已提交数据）。 */
    private Evaluation evaluate(Tournament t) {
        return doEvaluate(t);
    }

    /** 锁内评估（开赛事务内调用：并发开赛已由赛事行锁串行化，这里同样读取最新已提交版本）。 */
    private Evaluation evaluateLocked(Tournament t) {
        return doEvaluate(t);
    }

    private Evaluation doEvaluate(Tournament t) {
        List<StartCheckView.BlockedHole> blocked = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        Course course = t.getCourse() == null ? null
                : courseRepository.findById(t.getCourse().getId()).orElse(null);

        // 球场级阻断（holeNo 留空，前端作为球场行展示）
        if (course == null) {
            blocked.add(new StartCheckView.BlockedHole(null, "基础数据不完整", null, null,
                    "赛事「" + t.getName() + "」未绑定球场，无法读取应打球洞",
                    "请在「赛事 → 编辑」中为本场赛事选择绑定球场后重试"));
            return new Evaluation(List.of(), blocked, notices, null);
        }
        if (!Dict.isValidHoleCount(course.getHoleCount())) {
            blocked.add(new StartCheckView.BlockedHole(null, "基础数据不完整", null, null,
                    "球场「" + course.getName() + "」的应打洞数不合法（"
                            + course.getHoleCount() + "，须为 " + Dict.HOLE_COUNT_MIN + " ~ "
                            + Dict.HOLE_COUNT_MAX + "）",
                    "请先在球场管理中修正应打洞数"));
            return new Evaluation(List.of(), blocked, notices, null);
        }

        // 每次评估都重新查询全部球洞（不接收任何缓存 / 旧校验结果）；开赛调用方已持有赛事行锁，
        // 同一时刻不存在第二个开赛事务，因此读到的就是开赛瞬间最新已提交的球洞版本。
        List<Hole> allCourseHoles = holeRepository.findByCourseIdOrderByHoleNoAsc(course.getId());
        long versionHash = versionHash(allCourseHoles);
        Map<Integer, Hole> byNo = allCourseHoles.stream()
                .collect(Collectors.toMap(Hole::getHoleNo, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));

        List<Integer> eligible = new ArrayList<>();
        for (int no = 1; no <= course.getHoleCount(); no++) {
            Hole hole = byNo.get(no);
            if (hole == null) {
                blocked.add(new StartCheckView.BlockedHole(no, "未配置", null, null,
                        "第 " + no + " 洞尚未配置（球场「" + course.getName() + "」应打 "
                                + course.getHoleCount() + " 洞，洞号 1 ~ " + course.getHoleCount()
                                + " 必须连续配置齐全）",
                        "请在球洞管理为球场「" + course.getName() + "」补建第 " + no
                                + " 洞（par 3/4/5、码距 > 0、状态开放）"));
                continue;
            }
            StartCheckView.BlockedHole defect = inspectHole(hole);
            if (defect != null) {
                blocked.add(defect);
            } else {
                eligible.add(no);
            }
        }

        // 洞数之外的额外洞：不阻断本场（应打洞集只取 1 ~ holeCount），但要明确提示，避免误用
        for (Hole extra : allCourseHoles) {
            if (extra.getHoleNo() > course.getHoleCount()) {
                notices.add("球场「" + course.getName() + "」还配置了第 " + extra.getHoleNo()
                        + " 洞，超出本场应打洞数 " + course.getHoleCount()
                        + "，不进入本场应打洞集；当前状态：" + extra.getStatus());
            }
        }
        return new Evaluation(List.copyOf(eligible), blocked, notices, versionHash);
    }

    /** 逐洞合法性：开放 + par∈{3,4,5} + 码距 1~800；任一不满足返回阻断明细。 */
    private StartCheckView.BlockedHole inspectHole(Hole hole) {
        int no = hole.getHoleNo();
        if (!Dict.isValidHoleStatus(hole.getStatus())) {
            return new StartCheckView.BlockedHole(no, "基础数据不完整", hole.getPar(), hole.getYardage(),
                    "第 " + no + " 洞状态值无法识别：" + hole.getStatus(),
                    "请把第 " + no + " 洞状态修正为 开放 / 维护 / 封闭 之一");
        }
        if ("维护".equals(hole.getStatus())) {
            return new StartCheckView.BlockedHole(no, "维护", hole.getPar(), hole.getYardage(),
                    "第 " + no + " 洞正在维护封洞养护，不能进入开赛",
                    "请在球洞管理完成第 " + no + " 洞养护后改回「开放」再重试开赛");
        }
        if ("封闭".equals(hole.getStatus())) {
            return new StartCheckView.BlockedHole(no, "封闭", hole.getPar(), hole.getYardage(),
                    "第 " + no + " 洞已封闭（长期停用），不能进入开赛",
                    "如本场需要第 " + no + " 洞，请在球洞管理将其重新开放并核对 par / 码距");
        }
        if (!Dict.isValidPar(hole.getPar())) {
            return new StartCheckView.BlockedHole(no, "基础数据不完整", hole.getPar(), hole.getYardage(),
                    "第 " + no + " 洞标准杆不合法（" + hole.getPar() + "，须为 3 / 4 / 5）",
                    "请在球洞管理把第 " + no + " 洞标准杆修正为 3、4 或 5");
        }
        if (!Dict.isValidYardage(hole.getYardage())) {
            return new StartCheckView.BlockedHole(no, "基础数据不完整", hole.getPar(), hole.getYardage(),
                    "第 " + no + " 洞码距不合法（" + hole.getYardage() + "，须为 "
                            + Dict.YARDAGE_MIN + " ~ " + Dict.YARDAGE_MAX + " 码）",
                    "请在球洞管理把第 " + no + " 洞码距修正为正数（不超过 " + Dict.YARDAGE_MAX + " 码）");
        }
        return null;
    }

    /** 已开赛（进行中 / 已结束）视图：应打洞集读冻结记录，不随后续球洞维护而改变。 */
    private StartCheckView startedView(Tournament t, TournamentStartCheck persisted) {
        List<Integer> frozenNos;
        Long hash;
        LocalDateTime checkedAt;
        String attemptType;
        if (persisted != null && TournamentStartCheck.TYPE_PASSED.equals(persisted.getCheckType())) {
            frozenNos = parseNos(persisted.getEligibleHoleNos());
            hash = persisted.getCourseVersionHash();
            checkedAt = persisted.getCheckedAt();
            attemptType = persisted.getCheckType();
        } else {
            // 历史兼容：修复前已开赛 / 已结束、没有冻结记录的旧赛事，按当前球场开放洞只读还原并明确提示
            frozenNos = fallbackOpenNos(t);
            hash = null;
            checkedAt = null;
            attemptType = null;
        }
        List<String> notices = new ArrayList<>();
        if (persisted == null) {
            notices.add("该赛事在本功能上线前已开赛，没有冻结的开赛记录；以下应打洞集为按当前球场开放洞的只读兼容口径");
        }
        Course course = t.getCourse();
        return new StartCheckView(StartCheckView.PHASE_STARTED, false, false, true,
                t.getId(), t.getTournamentNo(), t.getName(), t.getStatus(), t.getVersion(),
                course == null ? null : course.getId(),
                course == null ? null : course.getName(),
                course == null ? null : course.getHoleCount(),
                frozenNos, List.of(), notices, hash, attemptType, checkedAt);
    }

    /** 组装报名中赛事的实时视图（可开赛 / 被阻止），并带上最近一次尝试留痕。 */
    private StartCheckView assemble(Tournament t, Evaluation eval, TournamentStartCheck persisted) {
        boolean blocked = !eval.blockedHoles().isEmpty();
        String phase = blocked ? StartCheckView.PHASE_BLOCKED : StartCheckView.PHASE_READY;
        Course course = t.getCourse();
        return new StartCheckView(phase, !blocked, blocked, false,
                t.getId(), t.getTournamentNo(), t.getName(), t.getStatus(), t.getVersion(),
                course == null ? null : course.getId(),
                course == null ? null : course.getName(),
                course == null ? null : course.getHoleCount(),
                eval.eligibleHoleNos(), List.copyOf(eval.blockedHoles()),
                List.copyOf(eval.notices()), eval.versionHash(),
                persisted == null ? null : persisted.getCheckType(),
                persisted == null ? null : persisted.getCheckedAt());
    }

    // ================================================================
    // 开赛冻结洞集：排行榜 / 记分卡的统一读取入口（本轮口径不随后续改洞变化）
    // ================================================================

    /**
     * 取赛事本轮应打球洞（已按洞号升序）：
     * <ul>
     *   <li>存在「可开赛」冻结记录：解析冻结洞号并按<b>当前洞实体</b>返回（par/码距展示用），
     *       冻结洞集不随后续维护变化；冻结后被删除的洞以缺失跳过（历史只读口径）。</li>
     *   <li>无冻结记录（历史旧赛事）：退回该赛事球场当前全部开放洞；
     *       赛事也未绑定球场时再退回全库开放洞（修复前旧数据兼容）。</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<Hole> eligibleHolesFor(Tournament tournament) {
        Optional<TournamentStartCheck> persisted =
                checkRepository.findByTournamentId(tournament.getId());
        if (persisted.isPresent()
                && TournamentStartCheck.TYPE_PASSED.equals(persisted.get().getCheckType())) {
            List<Integer> nos = parseNos(persisted.get().getEligibleHoleNos());
            Long courseId = tournament.getCourse() == null ? null : tournament.getCourse().getId();
            if (courseId != null) {
                Map<Integer, Hole> byNo = holeRepository.findByCourseIdOrderByHoleNoAsc(courseId)
                        .stream()
                        .collect(Collectors.toMap(Hole::getHoleNo, Function.identity(), (a, b) -> a));
                return nos.stream().map(byNo::get).filter(Objects::nonNull).toList();
            }
            // 极端兼容：冻结记录在但赛事球场已解绑，按洞号全库匹配
            return nos.stream()
                    .map(holeRepository::findByHoleNo)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(Hole::getHoleNo))
                    .toList();
        }
        return fallbackOpenHoles(tournament);
    }

    /** 历史兼容口径：绑定球场取该场开放洞，未绑定取全库开放洞。 */
    private List<Hole> fallbackOpenHoles(Tournament tournament) {
        if (tournament.getCourse() != null) {
            return holeRepository.findByCourseIdAndStatus(tournament.getCourse().getId(), "开放").stream()
                    .sorted(Comparator.comparing(Hole::getHoleNo))
                    .toList();
        }
        return holeRepository.findByStatus("开放").stream()
                .sorted(Comparator.comparing(Hole::getHoleNo))
                .toList();
    }

    private List<Integer> fallbackOpenNos(Tournament t) {
        return fallbackOpenHoles(t).stream().map(Hole::getHoleNo).toList();
    }

    // ================================================================
    // 辅助
    // ================================================================

    private String staleMessage(Long expected, Long current) {
        return "赛事数据已变化：你打开的是 v" + expected + "，当前已是 v" + current
                + "。本次开赛未生效，请重新读取当前赛事与球洞状态后再操作";
    }

    /** 球洞版本指纹：洞数、各洞 id/洞号/版本/状态/par/码距共同决定，任何维护都会使其变化。 */
    private long versionHash(List<Hole> holes) {
        long hash = 1469598103934665603L; // FNV-64 offset basis
        for (Hole h : holes) {
            hash = mix(hash, h.getId() == null ? 0L : h.getId());
            hash = mix(hash, h.getHoleNo() == null ? 0L : h.getHoleNo().longValue());
            hash = mix(hash, h.getVersion() == null ? 0L : h.getVersion());
            hash = mixString(hash, h.getStatus());
            hash = mix(hash, h.getPar() == null ? 0L : h.getPar().longValue());
            hash = mix(hash, h.getYardage() == null ? 0L : h.getYardage().longValue());
        }
        return hash;
    }

    private long mix(long hash, long value) {
        long h = hash ^ value;
        h *= 1099511628211L; // FNV prime
        return h;
    }

    private long mixString(long hash, String s) {
        long h = hash;
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                h = mix(h, s.charAt(i));
            }
        }
        return h;
    }

    private List<Integer> parseNos(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<Integer> nos = new ArrayList<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                nos.add(Integer.valueOf(trimmed));
            }
        }
        return nos;
    }

    private String toJson(List<StartCheckView.BlockedHole> blocked) {
        try {
            return objectMapper.writeValueAsString(blocked);
        } catch (JsonProcessingException e) {
            throw new BizException("开赛失败明细序列化失败：" + e.getMessage());
        }
    }

    /** 评估中间结果。eligible 为可进入开赛的洞号；blocked 为阻断明细。 */
    private record Evaluation(
            List<Integer> eligibleHoleNos,
            List<StartCheckView.BlockedHole> blockedHoles,
            List<String> notices,
            Long versionHash) {
    }
}
