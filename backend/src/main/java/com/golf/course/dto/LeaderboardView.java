package com.golf.course.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 赛事排行榜视图：前端区分「临时结果 / 正式快照 / 生成失败 / 历史兼容口径」的唯一数据结构，
 * 同时也是正式快照的冻结体（收口时原样序列化为 JSON 落库，读取时原样反序列化返回，
 * 不再回查成绩表，保证之后补录 / 修改 / 删除成绩都不会改变名次）。
 *
 * @param kind                       口径：临时结果 / 正式快照 / 生成失败 / 历史口径
 * @param provisional                是否临时结果（报名中 / 进行中实时计算，明确标注，非正式成绩）
 * @param frozen                     是否冻结的正式快照（读取永远返回这份，不随后续成绩变化）
 * @param legacy                     旧赛事兼容口径（修复前已结束、没有正式快照的赛事，只读标记）
 * @param tournamentVersion          当前赛事版本号：收口时回传做过期检测，过期操作收到 409
 * @param snapshotVersion            快照行版本号；临时结果没有快照行时为 null
 * @param failureReasons             收口失败逐条原因（含赛事、球员、洞号）；无失败时为空
 * @param notices                    非阻断性提示（临时结果下的缺洞 / 非参赛球员成绩等）
 * @param holeNos                    本场应打（开放）球洞号
 * @param rows                       名次行（正式快照为冻结名次；临时结果中未完赛球员无名次）
 */
public record LeaderboardView(
        String kind,
        boolean provisional,
        boolean frozen,
        boolean legacy,
        Long tournamentId,
        String tournamentNo,
        String tournamentName,
        String tournamentStatus,
        Long tournamentVersion,
        Long snapshotVersion,
        LocalDateTime generatedAt,
        List<String> failureReasons,
        List<String> notices,
        List<Integer> holeNos,
        List<Row> rows) {

    public static final String KIND_PROVISIONAL = "临时结果";
    public static final String KIND_OFFICIAL = "正式快照";
    public static final String KIND_FAILED = "生成失败";
    public static final String KIND_LEGACY = "历史口径";

    /**
     * 单行名次。
     *
     * @param rank        名次（仅全部应打轮次与球洞成绩齐全者有名次；未完赛为 null，页面显示「—」）
     * @param complete    是否成绩齐全
     * @param rounds      各轮明细（冻结体逐洞留痕）
     */
    public record Row(
            Long rank,
            Long playerId,
            String playerName,
            Integer totalStrokes,
            Integer totalPar,
            Integer toPar,
            boolean complete,
            List<Round> rounds) {
    }

    /**
     * 单轮明细。strokesByHole 为洞号 → 杆数（仅合法且属于该球员该轮的成绩进入）。
     */
    public record Round(
            Integer roundNo,
            Map<Integer, Integer> strokesByHole,
            Integer totalStrokes,
            Integer totalPar,
            boolean complete,
            List<Integer> missingHoles) {
    }
}
