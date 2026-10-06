package com.golf.course.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 定向重算请求：对指定球员逐个重算（每个球员独立事务，单个失败不影响其他人）。
 *
 * @param playerIds 受影响的球员 id 列表（如补齐球场难度参数后受影响的球员）
 * @param asOfDate  计算基准日；为空取当天。只取基准日（含）之前已结束的轮次
 */
public record RecalcRequest(List<Long> playerIds, LocalDate asOfDate) {
}
