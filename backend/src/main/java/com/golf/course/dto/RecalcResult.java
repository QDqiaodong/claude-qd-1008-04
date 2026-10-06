package com.golf.course.dto;

import java.math.BigDecimal;

/**
 * 定向重算的单球员结果：成功（含样本不足）或失败及原因，逐个返回不中断。
 */
public record RecalcResult(Long playerId, String playerName, boolean ok,
                           String status, BigDecimal indexValue, String message) {
}
