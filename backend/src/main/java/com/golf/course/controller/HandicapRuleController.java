package com.golf.course.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.golf.course.common.ApiResponse;
import com.golf.course.entity.HandicapRule;
import com.golf.course.service.HandicapRuleService;

/**
 * 差点计算规则维护：带生效日期的版本化规则。
 * 新规则只影响生效日之后（含）的计算；被引用的规则不可删除。
 */
@RestController
@RequestMapping("/api/handicap-rules")
public class HandicapRuleController {

    private final HandicapRuleService ruleService;

    public HandicapRuleController(HandicapRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @GetMapping
    public ApiResponse<List<HandicapRule>> list() {
        return ApiResponse.success(ruleService.list());
    }

    @PostMapping
    public ApiResponse<HandicapRule> create(@RequestBody HandicapRule input) {
        return ApiResponse.success("规则已创建，将作用于生效日之后（含）的计算", ruleService.create(input));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        ruleService.delete(id);
        return ApiResponse.success("规则已删除", null);
    }
}
