package com.golf.course.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.golf.course.common.BizException;
import com.golf.course.common.ConflictException;
import com.golf.course.common.Dict;
import com.golf.course.dto.LeaderboardView;
import com.golf.course.dto.StartCheckView;
import com.golf.course.entity.Course;
import com.golf.course.entity.Tournament;
import com.golf.course.repository.CourseRepository;
import com.golf.course.repository.TournamentRepository;

/**
 * 赛事业务。核心规则：状态只能逐级推进：报名中 → 进行中 → 已结束。
 *
 * <p>推进到「进行中」（开赛）必须先走 {@link TournamentStartService} 的开赛前球场可用性校验：
 * 按赛事绑定球场读取全部应打球洞，只有开放且 par / 码距合法的洞才放行，失败则赛事保持
 * 「报名中」并返回受影响洞号 / 当前状态 / 修复提示。</p>
 *
 * <p>推进到「已结束」不再只是改一个状态标记：收口必须在同一事务里通过排行榜快照服务完成
 * 「确认全部参赛成绩 → 校验（缺成绩 / 孤儿球员 / 洞号杆数不合法）→ 冻结唯一正式快照 → 翻状态」。
 * 任何一步失败整体回滚，赛事保持「进行中」，只留下失败原因留痕，绝不产生半份正式结果。</p>
 */
@Service
public class TournamentService {

    private final TournamentRepository tournamentRepository;
    private final LeaderboardSnapshotService leaderboardSnapshotService;
    private final TournamentStartService tournamentStartService;
    private final CourseRepository courseRepository;

    public TournamentService(TournamentRepository tournamentRepository,
                             LeaderboardSnapshotService leaderboardSnapshotService,
                             TournamentStartService tournamentStartService,
                             CourseRepository courseRepository) {
        this.tournamentRepository = tournamentRepository;
        this.leaderboardSnapshotService = leaderboardSnapshotService;
        this.tournamentStartService = tournamentStartService;
        this.courseRepository = courseRepository;
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
        validateDifficulty(input.getCourseRating(), input.getSlopeRating());
        Course course = resolveCourse(input);
        Tournament t = new Tournament();
        t.setTournamentNo(input.getTournamentNo().trim());
        t.setName(input.getName().trim());
        t.setCourse(course);
        t.setStartDate(input.getStartDate());
        t.setEndDate(input.getEndDate());
        t.setStatus(input.getStatus());
        t.setLocation(input.getLocation());
        t.setCourseRating(input.getCourseRating());
        t.setSlopeRating(input.getSlopeRating());
        t.setNote(input.getNote());
        t.setCreatedAt(LocalDateTime.now());
        return tournamentRepository.save(t);
    }

    /**
     * 维护赛事基础信息与球场难度参数（状态只能走 advance 逐级推进，不在此处改）。
     * 难度参数成对写：courseRating 与 slopeRating 要同时填写或同时留空——
     * 旧赛事补齐参数后，受影响球员可走差点定向重算。
     *
     * <p>绑定球场只允许在「报名中」阶段维护：一旦开赛，本轮应打球洞集已按当时球场冻结，
     * 换场会静默改变本轮口径，因此进行中 / 已结束的赛事改绑球场一律拒绝
     * （后续新赛事正常按新绑定重新校验）。</p>
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
        validateDifficulty(input.getCourseRating(), input.getSlopeRating());
        t.setCourseRating(input.getCourseRating());
        t.setSlopeRating(input.getSlopeRating());

        // 显式携带 course（id 非空即绑定、传 null 字段表示解绑）只在报名中允许
        if (input.getCourse() != null) {
            if (!"报名中".equals(t.getStatus())) {
                throw new BizException("赛事已「" + t.getStatus()
                        + "」，本轮应打球洞已按开赛时球场冻结，不能再改绑球场；"
                        + "请为后续新赛事选择球场并重新校验");
            }
            t.setCourse(resolveCourse(input));
        }
        if (t.getStartDate() != null && t.getEndDate() != null
                && t.getStartDate().isAfter(t.getEndDate())) {
            throw new BizException("开始日期不能晚于结束日期");
        }
        return tournamentRepository.save(t);
    }

    private Course resolveCourse(Tournament input) {
        if (input.getCourse() == null || input.getCourse().getId() == null) {
            return null;
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
     *   <li>报名中 → 进行中（开赛）：委托 {@link TournamentStartService}，在悲观行锁 +
     *       乐观版本双保护下按最新球洞版本做球场可用性校验，通过才冻结洞集并翻状态；
     *       返回 {@link StartCheckView}（已开始口径）。</li>
     *   <li>进行中 → 已结束（收口）：委托排行榜快照服务校验全部参赛成绩、
     *       冻结唯一正式快照并翻状态；返回 {@link LeaderboardView}（正式冻结口径）。</li>
     * </ul>
     *
     * @param expectedVersion 页面看到的赛事版本号；多人同时开赛 / 收口时过期一方收到 409
     */
    public Object advance(Long id, String target, Long expectedVersion) {
        Tournament t = get(id);
        if (!Dict.isValidTournamentStatus(target)) {
            throw new BizException("赛事状态不合法：" + target);
        }
        if (!Dict.canProgressTournament(t.getStatus(), target)) {
            throw new BizException("不允许从「" + t.getStatus() + "」直接转为「" + target + "」");
        }
        if ("进行中".equals(target)) {
            // 开赛校验失败（400，赛事仍报名中）与版本 / 并发冲突（409）由专用异常上抛，
            // 控制器统一返回按最新状态重算的校验视图。
            return tournamentStartService.start(id, expectedVersion);
        }
        if ("已结束".equals(target)) {
            if (expectedVersion != null && !expectedVersion.equals(t.getVersion())) {
                throw new ConflictException("赛事数据已变化：你打开的是 v" + expectedVersion
                        + "，当前已是 v" + t.getVersion()
                        + "。本次收口未生效，请重新读取当前排行榜与赛事状态后再操作");
            }
            // finalizeOfficial 自行编排独立事务（校验失败要先回滚再用新事务留痕），
            // 本方法不加外层事务，避免内层 TransactionTemplate 被迫加入外层事务而失去回滚边界。
            return leaderboardSnapshotService.finalizeOfficial(id, expectedVersion);
        }
        return null;
    }
}
