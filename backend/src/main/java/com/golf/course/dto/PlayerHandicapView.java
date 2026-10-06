package com.golf.course.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 球员差点详情：当前指数、最近变化、采用与排除的轮次（含差值与排除原因）、
 * 以及本次计算所依据的规则。
 */
public record PlayerHandicapView(
        Long playerId,
        String playerNo,
        String playerName,
        Integer baseHandicap,
        /** 已计算 / 样本不足 / 未计算 */
        String status,
        BigDecimal currentIndex,
        BigDecimal previousIndex,
        BigDecimal changeAmount,
        Boolean dropLimited,
        Boolean missingParams,
        Integer sampleSize,
        Integer usedCount,
        String ruleName,
        LocalDate ruleEffectiveDate,
        LocalDate asOfDate,
        LocalDateTime calculatedAt,
        String remark,
        List<RoundItem> rounds) {

    /** 进入计算窗口的单个轮次：差值、是否采用、排除原因、是否被截断 */
    public record RoundItem(
            Long tournamentId,
            String tournamentName,
            Integer roundNo,
            LocalDate playDate,
            Integer grossScore,
            BigDecimal courseRating,
            Integer slopeRating,
            BigDecimal differential,
            Boolean capped,
            Boolean used,
            String exclusionReason) {
    }
}
