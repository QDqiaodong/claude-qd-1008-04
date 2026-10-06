package com.golf.course.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 球员对具体球洞提出争议：可一次针对多个洞填写理由；未提及的洞视为无异议。
 */
public record DisputeRequest(
        @NotBlank(message = "球员姓名不能为空") String playerName,
        @NotNull(message = "缺少版本号，请刷新页面后重试") Long expectedVersion,
        @NotEmpty(message = "请至少选择一个争议球洞") List<Item> disputes) {

    public record Item(
            @NotNull(message = "争议洞号不能为空") Integer holeNo,
            @NotBlank(message = "争议理由不能为空") String reason) {
    }
}
