package com.golf.course.common;

import java.math.BigDecimal;
import java.util.List;

/**
 * 高尔夫球场词典与业务规则参数表：球洞状态、开球时间状态、球员差点阈值、
 * 赛事状态流转、成绩杆数与标准杆对比口径，以及各类阈值。
 *
 * <p>规则集中在此，便于出题时被要求调整或扩展。</p>
 */
public final class Dict {

    private Dict() {
    }

    // ---------------- 球洞状态 ----------------
    /** 开放 → 维护 → 开放；封闭为人工停用终态，可从维护进入 */
    public static final List<String> HOLE_STATUSES = List.of("开放", "维护", "封闭");

    /** 球场应打洞数合法区间（标准场 9 / 18 洞，口径上允许 1 ~ 18） */
    public static final int HOLE_COUNT_MIN = 1;
    public static final int HOLE_COUNT_MAX = 18;

    /** 球洞码距合法区间（码，>0 且不超过锦标场长度上限） */
    public static final int YARDAGE_MIN = 1;
    public static final int YARDAGE_MAX = 800;

    // ---------------- 开球时间状态 ----------------
    public static final List<String> TEE_STATUSES = List.of("可订", "已订");

    // ---------------- 球员差点 ----------------
    /** 差点取值范围 0 ~ 54（USGA 上限 54） */
    public static final int HANDICAP_MIN = 0;
    public static final int HANDICAP_MAX = 54;

    // ---------------- 赛事状态：报名中 → 进行中 → 已结束 ----------------
    public static final List<String> TOURNAMENT_FLOW = List.of("报名中", "进行中", "已结束");

    public static final List<String> TOURNAMENT_STATUSES = List.of("报名中", "进行中", "已结束");

    // ---------------- 成绩 ----------------
    /** 单洞最少杆数（一杆起） */
    public static final int MIN_STROKES = 1;

    // ---------------- 记分卡签核闭环 ----------------
    /** 记分卡状态：草稿 → 待球员确认 → 争议中 →（回到待球员确认）→ 已签核 */
    public static final String CARD_DRAFT = "草稿";
    public static final String CARD_PENDING = "待球员确认";
    public static final String CARD_DISPUTED = "争议中";
    public static final String CARD_SIGNED = "已签核";

    public static final List<String> SCORECARD_STATUSES =
            List.of(CARD_DRAFT, CARD_PENDING, CARD_DISPUTED, CARD_SIGNED);

    /** 流转事件动作 */
    public static final String ACTION_SUBMIT = "提交";
    public static final String ACTION_CONFIRM = "确认";
    public static final String ACTION_DISPUTE = "争议";
    public static final String ACTION_UPHOLD = "维持";
    public static final String ACTION_ADJUST = "修正";

    // ---------------- 差点指数重算 ----------------
    /** 差点计算状态：已计算 / 样本不足（可计轮次 < 规则最少轮次） */
    public static final String HCP_CALCULATED = "已计算";
    public static final String HCP_INSUFFICIENT = "样本不足";

    /** 批量重算批次状态 */
    public static final String RUN_RUNNING = "进行中";
    public static final String RUN_DONE = "已完成";
    public static final String RUN_PARTIAL = "部分失败";

    /** 差点指数上限（与 HANDICAP_MAX 对齐，下限 0） */
    public static final BigDecimal HCP_INDEX_MAX = new BigDecimal("54.0");
    public static final BigDecimal HCP_INDEX_MIN = BigDecimal.ZERO;

    /** 标准坡度值（WHS 差值公式中的 113） */
    public static final BigDecimal STANDARD_SLOPE = new BigDecimal("113");

    /** 球场难度参数合法区间：slope 55 ~ 155（WHS）；courseRating 覆盖短场到锦标场 */
    public static final int SLOPE_MIN = 55;
    public static final int SLOPE_MAX = 155;
    public static final BigDecimal RATING_MIN = new BigDecimal("20.0");
    public static final BigDecimal RATING_MAX = new BigDecimal("80.0");

    // ---------------- 取值方法 ----------------

    public static boolean isValidHoleStatus(String status) {
        return status != null && HOLE_STATUSES.contains(status);
    }

    /** 标准杆只接受 3 / 4 / 5（开赛校验与球洞建档共用同一口径） */
    public static boolean isValidPar(Integer par) {
        return par != null && par >= 3 && par <= 5;
    }

    /** 码距须为 1 ~ 800 的整数 */
    public static boolean isValidYardage(Integer yardage) {
        return yardage != null && yardage >= YARDAGE_MIN && yardage <= YARDAGE_MAX;
    }

    /** 球场应打洞数须为 1 ~ 18 */
    public static boolean isValidHoleCount(Integer holeCount) {
        return holeCount != null && holeCount >= HOLE_COUNT_MIN && holeCount <= HOLE_COUNT_MAX;
    }

    public static boolean isValidTeeStatus(String status) {
        return status != null && TEE_STATUSES.contains(status);
    }

    /** 差点必须落在 0 ~ 54 之间（含端点） */
    public static boolean isValidHandicap(Integer handicap) {
        return handicap != null && handicap >= HANDICAP_MIN && handicap <= HANDICAP_MAX;
    }

    public static boolean isValidTournamentStatus(String status) {
        return status != null && TOURNAMENT_STATUSES.contains(status);
    }

    /** 赛事状态只能逐级推进：报名中 → 进行中 → 已结束 */
    public static boolean canProgressTournament(String from, String to) {
        int i = TOURNAMENT_FLOW.indexOf(from);
        return i >= 0 && i + 1 < TOURNAMENT_FLOW.size() && TOURNAMENT_FLOW.get(i + 1).equals(to);
    }

    /** 坡度难度 slopeRating 须为 55 ~ 155 的整数（WHS 区间） */
    public static boolean isValidSlope(Integer slope) {
        return slope != null && slope >= SLOPE_MIN && slope <= SLOPE_MAX;
    }

    /** 球场难度值 courseRating 须落在 20.0 ~ 80.0 之间 */
    public static boolean isValidCourseRating(BigDecimal rating) {
        return rating != null && rating.compareTo(RATING_MIN) >= 0 && rating.compareTo(RATING_MAX) <= 0;
    }
}
