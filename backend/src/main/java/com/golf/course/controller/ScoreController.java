package com.golf.course.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.golf.course.common.ApiResponse;
import com.golf.course.entity.Score;
import com.golf.course.service.ScoreService;

@RestController
@RequestMapping("/api/scores")
public class ScoreController {

    private final ScoreService scoreService;

    public ScoreController(ScoreService scoreService) {
        this.scoreService = scoreService;
    }

    @GetMapping
    public ApiResponse<List<Score>> list(@RequestParam(required = false) Long tournamentId,
                                         @RequestParam(required = false) Long playerId) {
        return ApiResponse.success(scoreService.list(tournamentId, playerId));
    }

    /** 按洞录入成绩（与标准杆对比在实体派生字段 toPar 中体现） */
    @PostMapping
    public ApiResponse<Score> record(@RequestBody Score input) {
        return ApiResponse.success("成绩已录入", scoreService.record(input));
    }
}
