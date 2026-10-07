package com.golf.course.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.golf.course.common.BizException;
import com.golf.course.common.ConflictException;
import com.golf.course.common.Dict;
import com.golf.course.dto.LeaderboardView;
import com.golf.course.entity.Course;
import com.golf.course.entity.Tournament;
import com.golf.course.repository.CourseRepository;
import com.golf.course.repository.TournamentRepository;

/**
 * 赛事业务。核心规则：状态只能逐级推进：报名中 → 进行中 → 已结束。
 *
 * <p>推进到「进行中」（开赛）不再只是改一个状态标记：必须先通过
 * {@link TournamentStartService} 的开赛前球场可用性校验——按绑定球场读取全部应打球洞，
 * 只有开放且标准杆、码距均合法的洞允许进入开赛；校验失败赛事仍保持报名中，
 * 报名名单与成绩草稿不被清理，也不留下半个进行中状态。</p>
 *
 * <p>推进到「已结束」在同一事务里通过排行榜快照服务完成
 * 「确认全部参赛成绩 → 校验（缺成绩 / 孤儿球员 / 洞号杆数不合法）→ 冻结唯一正式快照 → 翻状态」。
 * 任何一步失败整体回滚，赛事保持「进行中」，只留下失败原因留痕，绝不产生半份正式结果。</p>
 */
@Service
public class TournamentService {

    private final TournamentRepository tournamentRepository;
    private final CourseRepository courseRepository;
    private final LeaderboardSnapshotService leaderboardSnapshotService;
    private final TournamentStartService tournamentStartService;

    public TournamentService(TournamentRepository tournamentRepository,
                             CourseRepository courseRepository,
                             LeaderboardSnapshotService leaderboardSnapshotService,
                             TournamentStartService tournamentStartService) {
        this.tournamentRepository = tournamentRepository;
        this.courseRepository = courseRepository;
        this.leaderboardSnapshotService = leaderboardSnapshotService;
        this.tournamentStartService = tournamentStartService;
    }

    public List<Tournament> list() {
        return tournamentRepository.findAll();
    }

    public Tournament get(Long id) {
        return tournamentRepository.findById(id)
                .orElseThrow(() -> new BizException("赛事不存在：" + id));
    }

    @Transactional
    public Tournament create(Tournament input) {
        if (input.getTournamentNo() == null || input.getTournamentNo().isBlank()) {
            throw new BizException("赛事编号不能为空");
        }
        tournamentRepository.findByTournamentNo(input.getTournamentNo().trim()).ifPresent(t -> {
            throw new BizException("赛事编号已存在：" + input.getTournamentNo());
        });
        if (input.getName() == null || input.getName().isBlank()) {
            throw new BizException("赛事名称不能为空");
        }
        if (!Dict.isValidTournamentStatus(input.getStatus())) {
            input.setStatus("报名中");
        }
        // 新建赛事一律从报名中起步；不允许直接建成进行中 / 已结束绕过开赛校验。
        if (!"报名中".equals(input.getStatus())) {
            throw new BizException("新建赛事必须处于「报名中」，开赛校验通过后才能推进到「进行中」");
        }
        validateDifficulty(input.getCourseRating(), input.getSlopeRating());
        Course course = resolveCourse(input);
        Tournament t = new Tournament();
        t.setTournamentNo(input.getTournamentNo().trim());
        t.setName(input.getName().trim());
        t.setStartDate(input.getStartDate());
        t.setEndDate(input.getEndDate());
        t.setStatus("报名中");
        t.setLocation(input.getLocation());
        t.setCourse(course);
        t.setCourseRating(input.getCourseRating());
        t.setSlopeRating(input.getSlopeRating());
        t.setNote(input.getNote());
        t.setCreatedAt(LocalDateTime.now());
        return tournamentRepository.save(t);
    }

    /**
     * 维护赛事基础信息与球场难度参数（状态只能走 advance 逐级推进，不在此处改）。
     * 球场绑定只允许在「报名中」阶段设置 / 更换；一旦开赛，本轮应打球洞已冻结，改绑不影响本轮。
     * 难度参数成对写：courseRating 与 slopeRating 要同时填写或同时留空。
     */
    @Transactional
    public Tournament update(Long id, Tournament input) {
        Tournament t = get(id);
        if (input.getName() != null && !input.getName().isBlank()) {
            t.setName(input.getName().trim());
        }
        if (input.getStartDate() != null) {
            t.setStartDate(input.getStartDate());
        }
        if (input.getEndDate() != null) {
            t.setEndDate(input.getEndDate());
        }
        if (input.getLocation() != null) {
            t.setLocation(input.getLocation());
        }
        if (input.getNote() != null) {
            t.setNote(input.getNote());
        }
        // 球场绑定：入参显式带 course（含 id）时才处理；已开赛后禁止改绑。
        if (input.getCourse() != null && input.getCourse().getId() != null) {
            if (!"报名中".equals(t.getStatus())) {
                throw new BizException("赛事已" + ("已结束".equals(t.getStatus()) ? "结束" : "开赛")
                        + "，本轮球场（" + courseName(t) + "）与应打球洞已冻结，不能改绑；"
                        + "请在新建赛事时选择该球场");
            }
            t.setCourse(resolveCourse(input));
        }
        validateDifficulty(input.getCourseRating(), input.getSlopeRating());
        t.setCourseRating(input.getCourseRating());
        t.setSlopeRating(input.getSlopeRating());
        if (t.getStartDate() != null && t.getEndDate() != null
                && t.getStartDate().isAfter(t.getEndDate())) {
            throw new BizException("开始日期不能晚于结束日期");
        }
        return tournamentRepository.save(t);
    }

    private String courseName(Tournament t) {
        return t.getCourse() == null ? "未绑定" : t.getCourse().getName();
    }

    private Course resolveCourse(Tournament input) {
        if (input.getCourse() == null || input.getCourse().getId() == null) {
            throw new BizException("赛事必须绑定开赛球场：请选择球场后再保存，开赛前系统会按该球场逐洞校验可用性");
        }
        return courseRepository.findById(input.getCourse().getId())
                .orElseThrow(() -> new BizException("绑定球场不存在：" + input.getCourse().getId()));
    }

    /** 球场难度参数需成对维护，且落在合法区间（缺参是允许的——旧数据明确按缺项处理）。 */
    private void validateDifficulty(java.math.BigDecimal courseRating, Integer slopeRating) {
        if ((courseRating == null) != (slopeRating == null)) {
            throw new BizException("球场难度参数需成对维护：courseRating 与 slopeRating 要同时填写或同时留空");
        }
        if (courseRating != null && !Dict.isValidCourseRating(courseRating)) {
            throw new BizException("球场难度值 courseRating 须在 " + Dict.RATING_MIN + " ~ "
                    + Dict.RATING_MAX + " 之间，当前：" + courseRating);
        }
        if (slopeRating != null && !Dict.isValidSlope(slopeRating)) {
            throw new BizException("坡度难度 slopeRating 须为 " + Dict.SLOPE_MIN + " ~ "
                    + Dict.SLOPE_MAX + " 的整数，当前：" + slopeRating);
        }
    }

    /**
     * 状态推进。
     * <ul>
     *   <li>推进到「进行中」（开赛）：委托开赛校验服务，悲观锁 + 乐观版本双保护下读取最新球洞版本，
     *       逐洞校验通过才冻结本轮球洞并翻状态；校验失败抛 {@link com.golf.course.common.StartBlockedException}
     *       （400，带逐洞明细），版本过期 / 并发开赛抛 {@link ConflictException}（409，带当前状态）。</li>
     *   <li>推进到「已结束」：委托排行榜快照服务收口。</li>
     * </ul>
     *
     * @param expectedVersion 页面看到的赛事版本号；多人同时操作时过期一方收到 409
     * @return 开赛成功返回新状态赛事；收口成功返回冻结的正式排行榜视图
     */
    public Object advance(Long id, String target, Long expectedVersion) {
        return advance(id, target, expectedVersion, null);
    }

    /**
     * @param expectedHoleVersion 页面看到的球洞版本指纹（仅开赛使用）：
     *                            页面打开后球洞被维护过则指纹不一致，返回 409 要求重读最新洞状态。
     */
    public Object advance(Long id, String target, Long expectedVersion, String expectedHoleVersion) {
        Tournament t = get(id);
        if (!Dict.isValidTournamentStatus(target)) {
            throw new BizException("赛事状态不合法：" + target);
        }
        if ("进行中".equals(target)) {
            // 开赛（或重复点击「开始」）统一交给开赛编排：
            // 报名中 → 校验最新洞版本后冻结球洞并翻状态；已被其他人开始 / 已结束 → 409 并返回当前状态。
            // 开赛编排自行加锁与管理事务边界（校验失败要先回滚再用新事务写审计流水）。
            return tournamentStartService.start(id, expectedVersion, expectedHoleVersion);
        }
        if (!Dict.canProgressTournament(t.getStatus(), target)) {
            throw new BizException("不允许从「" + t.getStatus() + "」直接转为「" + target + "」");
        }
        if ("已结束".equals(target)) {
            if (expectedVersion != null && !expectedVersion.equals(t.getVersion())) {
                throw new ConflictException("赛事数据已变化：你打开的是 v" + expectedVersion
                        + "，当前已是 v" + t.getVersion()
                        + "。本次收口未生效，请重新读取当前排行榜与赛事状态后再操作");
            }
            return leaderboardSnapshotService.finalizeOfficial(id, expectedVersion);
        }
        return null;
    }
}
