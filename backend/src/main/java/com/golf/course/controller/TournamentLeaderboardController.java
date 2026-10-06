package com.golf.course.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.golf.course.common.ApiResponse;
import com.golf.course.entity.TournamentLeaderboard;
import com.golf.course.service.TournamentLeaderboardService;

@RestController
@RequestMapping("/api/leaderboards")
public class TournamentLeaderboardController {

    private final TournamentLeaderboardService leaderboardService;

    public TournamentLeaderboardController(TournamentLeaderboardService leaderboardService) {
        this.leaderboardService = leaderboardService;
    }

    @GetMapping
    public ApiResponse<List<TournamentLeaderboard>> list(
            @RequestParam(required = false) Long tournamentId) {
        return ApiResponse.success(leaderboardService.list(tournamentId));
    }

    @GetMapping("/{id}")
    public ApiResponse<TournamentLeaderboard> get(@PathVariable Long id) {
        return ApiResponse.success(leaderboardService.get(id));
    }

    @PostMapping
    public ApiResponse<TournamentLeaderboard> create(@RequestBody TournamentLeaderboard input) {
        return ApiResponse.success("排行榜已创建", leaderboardService.create(input));
    }

    /**
     * 锁定并读取：以 OPTIMISTIC_FORCE_INCREMENT 乐观锁方式读取排行榜，
     * 每次调用把版本号 +1，用于演示并发预订竞态。
     */
    @PostMapping("/{id}/lock-and-read")
    public ApiResponse<TournamentLeaderboard> lockAndRead(@PathVariable Long id) {
        return ApiResponse.success("已锁定并读取（版本号已自增）", leaderboardService.lockAndRead(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<TournamentLeaderboard> update(@PathVariable Long id,
                                                    @RequestBody TournamentLeaderboard input) {
        return ApiResponse.success("已更新", leaderboardService.update(id, input));
    }
}
