package com.golf.course.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 球员历次差点计算记录（历史追溯）：每次计算的基准日、规则、指数与变化。
 */
public record HandicapHistoryItem(
        Long historyId,
        LocalDate asOfDate,
        LocalDateTime calculatedAt,
        String status,
        BigDecimal indexValue,
        BigDecimal previousIndex,
        BigDecimal changeAmount,
        Boolean dropLimited,
        Integer sampleSize,
        Integer usedCount,
        String ruleName,
        LocalDate ruleEffectiveDate,
        Long runId,
        String remark) {
}
