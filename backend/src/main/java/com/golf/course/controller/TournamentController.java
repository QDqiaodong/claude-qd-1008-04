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
import com.golf.course.dto.LeaderboardView;
import com.golf.course.entity.Tournament;
import com.golf.course.service.LeaderboardSnapshotService;
import com.golf.course.service.TournamentService;

@RestController
@RequestMapping("/api/tournaments")
public class TournamentController {

    private final TournamentService tournamentService;
    private final LeaderboardSnapshotService leaderboardSnapshotService;

    public TournamentController(TournamentService tournamentService,
                                LeaderboardSnapshotService leaderboardSnapshotService) {
        this.tournamentService = tournamentService;
        this.leaderboardSnapshotService = leaderboardSnapshotService;
    }

    @GetMapping
    public ApiResponse<List<Tournament>> list() {
        return ApiResponse.success(tournamentService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Tournament> get(@PathVariable Long id) {
        return ApiResponse.success(tournamentService.get(id));
    }

    @PostMapping
    public ApiResponse<Tournament> create(@RequestBody Tournament input) {
        return ApiResponse.success("赛事已创建", tournamentService.create(input));
    }

    /** 维护基础信息与球场难度参数（状态仍只能走 advance 逐级推进） */
    @PutMapping("/{id}")
    public ApiResponse<Tournament> update(@PathVariable Long id, @RequestBody Tournament input) {
        return ApiResponse.success("赛事已更新", tournamentService.update(id, input));
    }

    /**
     * 状态推进：报名中 → 进行中 → 已结束。
     * 推进到「已结束」= 赛事收口：成功时返回冻结的唯一正式排行榜快照（之后永远只返回这份）；
     * 成绩校验失败返回 400 且赛事保持「进行中」；并发收口 / 版本过期返回 409。
     */
    @PostMapping("/{id}/advance")
    public ApiResponse<?> advance(@PathVariable Long id,
                                  @RequestParam String target,
                                  @RequestParam(required = false) Long expectedVersion) {
        LeaderboardView official = tournamentService.advance(id, target, expectedVersion);
        if (official != null) {
            return ApiResponse.success("赛事已收口，正式排行榜快照已生成（名次已冻结，不再随成绩变化）",
                    official);
        }
        return ApiResponse.success("赛事状态已推进", tournamentService.get(id));
    }

    /**
     * 读取赛事排行榜（收口前后同一只读入口，天然保持数据边界）：
     * 报名中 / 进行中 → 明确标注的临时结果；已结束 → 唯一正式快照（原样返回冻结 JSON）；
     * 收口失败 → 返回失败原因 + 当前临时排名；旧赛事 → 只读历史口径（兼容修复前数据）。
     */
    @GetMapping("/{id}/leaderboard")
    public ApiResponse<LeaderboardView> leaderboard(@PathVariable Long id) {
        return ApiResponse.success(leaderboardSnapshotService.getView(id));
    }

    /**
     * 赛事收口（与推进到「已结束」等价的显式入口）：确认全部参赛成绩并冻结唯一正式快照。
     * expectedVersion 为页面加载时的赛事版本，多人同时收口只允许一个版本成功，过期 409。
     */
    @PostMapping("/{id}/leaderboard/finalize")
    public ApiResponse<LeaderboardView> finalizeLeaderboard(
            @PathVariable Long id,
            @RequestParam(required = false) Long expectedVersion) {
        return ApiResponse.success("赛事已收口，正式排行榜快照已生成（名次已冻结，不再随成绩变化）",
                leaderboardSnapshotService.finalizeOfficial(id, expectedVersion));
    }
}
