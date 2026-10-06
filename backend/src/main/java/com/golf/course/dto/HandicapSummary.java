package com.golf.course.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 球员列表页的差点汇总：每个球员最新一次计算的关键信息。
 */
public record HandicapSummary(Long playerId, String status, BigDecimal currentIndex,
                              BigDecimal changeAmount, Boolean dropLimited,
                              LocalDate asOfDate, LocalDateTime calculatedAt) {
}
