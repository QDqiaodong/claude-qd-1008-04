package com.golf.course.dto;

import java.util.List;

/**
 * 赛事开赛可用性校验视图：赛事页展示「校验中 / 可开赛 / 被哪些球洞阻止 / 已开始」四种状态的统一数据结构。
 *
 * <p>每次查询 / 开赛尝试都<b>实时读取最新球洞版本</b>组装，绝不复用任何一次历史失败的结果：
 * 管理员修复球洞后重新校验，本视图立刻反映修复后的最新状态。</p>
 *
 * @param state             校验状态：可开赛 / 已阻止 / 已开始（前端在请求未返回期间自行展示「校验中」）
 * @param tournamentId      赛事 ID
 * @param tournamentName    赛事名称
 * @param tournamentNo      赛事编号
 * @param tournamentStatus  赛事当前状态：报名中 / 进行中 / 已结束
 * @param tournamentVersion 赛事当前乐观锁版本（开赛请求须回传，过期 409）
 * @param courseId          绑定球场 ID；未绑定为 null（基础数据不完整，阻止开赛）
 * @param courseName        绑定球场名称
 * @param expectedHoleCount 球场应配置洞数（1..N 为应打洞号）
 * @param holeVersionToken  当前应打球洞的版本指纹（由各洞 id:version 汇总）；
 *                          页面发起开赛时回传，期间任何一洞被维护都会使其变化 → 409
 * @param checkedAt         校验时间
 * @param blockedHoles      阻止开赛的球洞明细（洞号 / 当前状态 / 标准杆 / 码距 / 原因 / 修复提示）
 * @param readyHoleNos      通过校验、开赛时允许进入本轮的球洞号
 * @param blocking          是否阻止开赛（blockedHoles 非空即为 true）
 */
public record StartCheckView(
        String state,
        Long tournamentId,
        String tournamentName,
        String tournamentNo,
        String tournamentStatus,
        Long tournamentVersion,
        Long courseId,
        String courseName,
        Integer expectedHoleCount,
        String holeVersionToken,
        java.time.LocalDateTime checkedAt,
        List<BlockedHole> blockedHoles,
        List<Integer> readyHoleNos,
        boolean blocking) {

    public static final String STATE_READY = "可开赛";
    public static final String STATE_BLOCKED = "已阻止";
    public static final String STATE_STARTED = "已开始";

    /**
     * 单个被阻止的球洞。
     *
     * @param holeNo  洞号；应配置但未建档时为该应打洞号
     * @param status  当前状态：维护 / 封闭 / 未配置 / 数据异常
     * @param par     当前标准杆（未配置 / 缺失时为 null）
     * @param yardage 当前码距（未配置 / 缺失时为 null）
     * @param reason  阻止原因（一句话说明）
     * @param fixHint 修复提示（管理员可照此修复后重试）
     */
    public record BlockedHole(
            Integer holeNo,
            String status,
            Integer par,
            Integer yardage,
            String reason,
            String fixHint) {
    }
}
