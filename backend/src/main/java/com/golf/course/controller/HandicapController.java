package com.golf.course.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.golf.course.common.ApiResponse;
import com.golf.course.dto.HandicapHistoryItem;
import com.golf.course.dto.HandicapSummary;
import com.golf.course.dto.MissingParamsView;
import com.golf.course.dto.PlayerHandicapView;
import com.golf.course.dto.RecalcRequest;
import com.golf.course.dto.RecalcResult;
import com.golf.course.service.HandicapService;

/**
 * 差点指数：球员页展示（当前指数 / 最近变化 / 采用与排除轮次 / 对应规则）、
 * 历史追溯、缺参清单与定向重算。
 */
@RestController
@RequestMapping("/api/handicaps")
public class HandicapController {

    private final HandicapService handicapService;

    public HandicapController(HandicapService handicapService) {
        this.handicapService = handicapService;
    }

    /** 每个球员最新一次计算汇总（球员列表页用） */
    @GetMapping("/summary")
    public ApiResponse<List<HandicapSummary>> summary() {
        return ApiResponse.success(handicapService.summary());
    }

    /** 球员差点详情：当前指数、最近变化、采用与排除的轮次、对应规则 */
    @GetMapping("/players/{playerId}")
    public ApiResponse<PlayerHandicapView> playerView(@PathVariable Long playerId) {
        return ApiResponse.success(handicapService.playerView(playerId));
    }

    /** 球员历次计算记录（按当时规则可追溯） */
    @GetMapping("/players/{playerId}/history")
    public ApiResponse<List<HandicapHistoryItem>> history(@PathVariable Long playerId) {
        return ApiResponse.success(handicapService.history(playerId));
    }

    /** 缺球场难度参数的赛事及受影响球员（旧数据明确缺项） */
    @GetMapping("/missing-params")
    public ApiResponse<List<MissingParamsView>> missingParams() {
        return ApiResponse.success(handicapService.missingParams());
    }

    /** 定向重算：只重算指定球员（如补齐难度参数后受影响的球员） */
    @PostMapping("/recalc")
    public ApiResponse<List<RecalcResult>> recalc(@RequestBody RecalcRequest req) {
        return ApiResponse.success("定向重算完成", handicapService.recalcTargeted(req));
    }
}
