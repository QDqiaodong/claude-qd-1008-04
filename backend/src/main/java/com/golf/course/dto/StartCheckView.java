package com.golf.course.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 赛事开赛前球场可用性校验视图：前端展示「校验中 / 可开赛 / 被哪些球洞阻止 / 已开始」
 * 四种状态、以及接口失败时（400 / 409）回传当前赛事与球洞状态的统一数据结构。
 *
 * <p>每次 GET 与每次开赛尝试都按<b>最新球洞版本</b>实时重算，不复用任何一次旧结果；
 * 已开赛后视图中的 {@link #eligibleHoleNos} 来自开赛瞬间冻结并落库的洞集，
 * 之后球洞被改成维护 / 封闭也不改变本轮口径。</p>
 *
 * @param phase            页面四态：校验中 / 可开赛 / 被阻止 / 已开始
 * @param canStart         是否允许此刻开赛（可开赛 = true；其余为 false）
 * @param blocked          是否存在阻断（被阻止 = true）
 * @param frozen           是否已开赛冻结（已开始 = true，本轮洞集不再随球洞变化）
 * @param tournamentStatus 当前赛事状态：报名中 / 进行中 / 已结束
 * @param tournamentVersion 当前赛事版本号（开赛时回传做过期检测，过期收 409）
 * @param courseId         绑定球场 ID；未绑定为 null
 * @param courseName       绑定球场名称；未绑定为 null
 * @param expectedHoleCount 球场应打洞数（1 ~ holeCount）；未绑定球场为 null
 * @param eligibleHoleNos  校验通过 / 已冻结的应打球洞号；被阻止或未绑定时为逐洞状态仍由 blockedHoles 给出
 * @param blockedHoles     阻断明细（受影响洞号 / 当前状态 / par / yardage / 修复提示），无阻断为空
 * @param notices          非阻断提示（应打洞数之外的额外洞等）
 * @param courseVersionHash 校验时刻球洞版本指纹
 * @param lastAttemptType  最近一次开赛尝试留痕：可开赛 / 被阻止；从未尝试为 null
 * @param checkedAt        最近一次开赛尝试时间
 */
public record StartCheckView(
        String phase,
        boolean canStart,
        boolean blocked,
        boolean frozen,
        Long tournamentId,
        String tournamentNo,
        String tournamentName,
        String tournamentStatus,
        Long tournamentVersion,
        Long courseId,
        String courseName,
        Integer expectedHoleCount,
        List<Integer> eligibleHoleNos,
        List<BlockedHole> blockedHoles,
        List<String> notices,
        Long courseVersionHash,
        String lastAttemptType,
        LocalDateTime checkedAt) {

    /** 页面四态 */
    public static final String PHASE_CHECKING = "校验中";
    public static final String PHASE_READY = "可开赛";
    public static final String PHASE_BLOCKED = "被阻止";
    public static final String PHASE_STARTED = "已开始";

    /**
     * 单个受影响球洞的阻断明细。
     *
     * @param holeNo       洞号；未配置洞为应配未配的洞号；球场级问题（未绑定球场 / 洞数非法）为 null
     * @param currentStatus 当前状态：开放 / 维护 / 封闭 / 未配置 / 基础数据不完整
     * @param par          当前标准杆（未配置为 null）
     * @param yardage      当前码距（未配置为 null）
     * @param reason       阻断原因
     * @param fixHint      修复提示（管理员可据此操作后重试）
     */
    public record BlockedHole(
            Integer holeNo,
            String currentStatus,
            Integer par,
            Integer yardage,
            String reason,
            String fixHint) {
    }
}
