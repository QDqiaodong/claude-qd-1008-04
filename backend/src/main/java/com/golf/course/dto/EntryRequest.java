package com.golf.course.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 登记参赛球员（界定赛事收口的签核范围）。
 */
public record EntryRequest(
        @NotNull(message = "必须指定球员") Long playerId,
        @NotNull(message = "应打轮次不能为空") @Min(value = 1, message = "轮次必须 ≥ 1") Integer roundCount) {
}
