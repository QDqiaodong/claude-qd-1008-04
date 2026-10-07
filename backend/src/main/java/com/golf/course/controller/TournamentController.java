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
import com.golf.course.dto.StartCheckView;
import com.golf.course.entity.Tournament;
import com.golf.course.service.LeaderboardSnapshotService;
import com.golf.course.service.TournamentService;

@RestController
@RequestMapping("/api/tournaments")
public class TournamentController {

    private final TournamentService tournamentService;
    private final LeaderboardSnapshotService leaderboardSnapshotService;
    private final com.golf.course.service.TournamentStartService tournamentStartService;

    public TournamentController(TournamentService tournamentService,
                                LeaderboardSnapshotService leaderboardSnapshotService,
                                com.golf.course.service.TournamentStartService tournamentStartService) {
        this.tournamentService = tournamentService;
        this.leaderboardSnapshotService = leaderboardSnapshotService;
        this.tournamentStartService = tournamentStartService;
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

    /** 维护基础信息、绑定球场与球场难度参数（状态仍只能走 advance 逐级推进） */
    @PutMapping("/{id}")
    public ApiResponse<Tournament> update(@PathVariable Long id, @RequestBody Tournament input) {
        return ApiResponse.success("赛事已更新", tournamentService.update(id, input));
    }

    /**
     * 开赛前球场可用性校验（只读）：实时读取赛事绑定球场的<b>最新球洞版本</b>，
     * 返回「可开赛 / 已阻止 / 已开始」视图；被阻止时逐洞列出洞号、当前状态与修复提示。
     * 该接口绝不复用历史失败结果，修复球洞后重新调用即为最新判定。
     */
    @GetMapping("/{id}/start-check")
    public ApiResponse<StartCheckView> startCheck(@PathVariable Long id) {
        return ApiResponse.success(tournamentStartService.check(id));
    }

    /**
     * 状态推进：报名中 → 进行中 → 已结束。
     * 开赛（→进行中）：服务端按最新球洞版本逐洞校验，通过才冻结本轮球洞并翻状态；
     *   校验失败 400（data 为逐洞阻止明细，赛事仍报名中，名单 / 草稿保留）；
     *   并发开赛 / 版本过期 / 球洞刚被维护 409（data 为当前赛事与球洞最新状态）。
     * 收口（→已结束）：成功时返回冻结的唯一正式排行榜快照；成绩校验失败 400；版本过期 409。
     */
    @PostMapping("/{id}/advance")
    public ApiResponse<?> advance(@PathVariable Long id,
                                  @RequestParam String target,
                                  @RequestParam(required = false) Long expectedVersion,
                                  @RequestParam(required = false) String expectedHoleVersion) {
        Object result = tournamentService.advance(id, target, expectedVersion, expectedHoleVersion);
        if (result instanceof LeaderboardView official) {
            return ApiResponse.success("赛事已收口，正式排行榜快照已生成（名次已冻结，不再随成绩变化）",
                    official);
        }
        if ("进行中".equals(target)) {
            return ApiResponse.success("球场可用性校验通过，赛事已开赛：本轮应打球洞已冻结，"
                    + "之后维护球洞为维护 / 封闭不会改变本轮状态", result);
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
