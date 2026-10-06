package com.golf.course.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 球员整卡确认。已签核终态不可撤回；过期版本返回 409。
 */
public record ConfirmCardRequest(
        @NotBlank(message = "球员姓名不能为空") String playerName,
        @NotNull(message = "缺少版本号，请刷新页面后重试") Long expectedVersion) {
}
