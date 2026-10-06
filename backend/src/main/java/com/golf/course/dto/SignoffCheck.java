package com.golf.course.dto;

import java.util.List;

/**
 * 赛事收口（进行中 → 已结束）前的签核检查结果。
 *
 * @param canClose         是否允许收口
 * @param entryCount       参赛名额数
 * @param signedRounds     已签核的（名额 × 轮次）数
 * @param blockingReasons  阻止收口的逐条原因（待确认 / 争议中 / 草稿 / 缺卡 等）
 */
public record SignoffCheck(
        boolean canClose,
        int entryCount,
        int signedRounds,
        List<String> blockingReasons) {
}
