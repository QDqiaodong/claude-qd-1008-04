package com.golf.course.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 保存草稿逐洞行：记分员按赛事 × 轮次 × 球员维护单洞杆数。
 */
public record SaveHoleRequest(
        @NotNull(message = "洞号不能为空") Integer holeNo,
        @NotNull(message = "杆数不能为空") @Min(value = 1, message = "单洞杆数须 ≥ 1") Integer strokes,
        Long expectedVersion) {
}
