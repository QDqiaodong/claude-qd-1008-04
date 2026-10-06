package com.golf.course.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 记分员提交记分卡：必须携带页面加载时的版本号做乐观锁校验。
 */
public record SubmitCardRequest(
        @NotBlank(message = "记分员姓名不能为空") String submitter,
        @NotNull(message = "缺少版本号，请刷新页面后重试") Long expectedVersion) {
}
