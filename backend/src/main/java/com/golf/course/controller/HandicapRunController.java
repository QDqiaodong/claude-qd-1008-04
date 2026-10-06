package com.golf.course.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.golf.course.common.ApiResponse;
import com.golf.course.dto.RunRequest;
import com.golf.course.entity.HandicapRun;
import com.golf.course.entity.HandicapRunError;
import com.golf.course.service.HandicapRunService;

/**
 * 全量差点重算批次：按赛事日期分批执行，逐球员独立事务，失败明细可查。
 */
@RestController
@RequestMapping("/api/handicap-runs")
public class HandicapRunController {

    private final HandicapRunService runService;

    public HandicapRunController(HandicapRunService runService) {
        this.runService = runService;
    }

    @GetMapping
    public ApiResponse<List<HandicapRun>> list() {
        return ApiResponse.success(runService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<HandicapRun> get(@PathVariable Long id) {
        return ApiResponse.success(runService.get(id));
    }

    /** 批次失败明细（批次 / 球员 / 原因） */
    @GetMapping("/{id}/errors")
    public ApiResponse<List<HandicapRunError>> errors(@PathVariable Long id) {
        return ApiResponse.success(runService.errors(id));
    }

    /** 启动全量重算：{asOfDate?, fromDate?, toDate?, batchSize?, triggeredBy?} */
    @PostMapping
    public ApiResponse<HandicapRun> execute(@RequestBody RunRequest req) {
        return ApiResponse.success("全量重算已执行", runService.execute(req));
    }
}
