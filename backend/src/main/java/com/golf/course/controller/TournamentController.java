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
import com.golf.course.service.TournamentStartService;

@RestController
@RequestMapping("/api/tournaments")
public class TournamentController {

    private final TournamentService tournamentService;
    private final LeaderboardSnapshotService leaderboardSnapshotService;
    private final TournamentStartService tournamentStartService;

    public TournamentController(TournamentService tournamentService,
                                LeaderboardSnapshotService leaderboardSnapshotService,
                                TournamentStartService tournamentStartService) {
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

    /** 维护基础信息与球场难度参数（状态仍只能走 advance 逐级推进；绑定球场仅报名中可改） */
    @PutMapping("/{id}")
    public ApiResponse<Tournament> update(@PathVariable Long id, @RequestBody Tournament input) {
        return ApiResponse.success("赛事已更新", tournamentService.update(id, input));
    }

    /**
     * 状态推进：报名中 → 进行中 → 已结束。
     * 推进到「进行中」= 开赛：按最新球洞版本做球场可用性校验，通过返回「已开始」校验视图；
     * 球洞阻断返回 400（响应体 data 为最新校验视图，赛事仍报名中）；
     * 并发开赛 / 版本过期返回 409（data 同样为当前赛事与球洞状态）。
     * 推进到「已结束」= 赛事收口：成功时返回冻结的唯一正式排行榜快照。
     */
    @PostMapping("/{id}/advance")
    public ApiResponse<?> advance(@PathVariable Long id,
                                  @RequestParam String target,
                                  @RequestParam(required = false) Long expectedVersion) {
        Object result = tournamentService.advance(id, target, expectedVersion);
        if (result instanceof LeaderboardView official) {
            return ApiResponse.success("赛事已收口，正式排行榜快照已生成（名次已冻结，不再随成绩变化）",
                    official);
        }
        if (result instanceof StartCheckView start) {
            return ApiResponse.success("球场可用性校验通过，赛事已开赛（应打球洞集已冻结）", start);
        }
        return ApiResponse.success("赛事状态已推进", tournamentService.get(id));
    }

    /**
     * 读取开赛前球场可用性校验（永远按最新球洞版本实时重算，只读、不留痕）：
     * 报名中 → 可开赛 / 被阻止（列出受影响洞号、当前状态、par、码距与修复提示）；
     * 进行中 / 已结束 → 已开始（返回开赛瞬间冻结的应打洞集，不随后续改洞变化）。
     */
    @GetMapping("/{id}/start-check")
    public ApiResponse<StartCheckView> startCheck(@PathVariable Long id) {
        return ApiResponse.success(tournamentStartService.getView(id));
    }

    /**
     * 显式开赛入口（与推进到「进行中」等价，但固定返回开赛校验视图）：
     * 多人同时点「开始」时只有基于最新赛事版本、且按最新球洞校验通过的一次成功；
     * 阻断返回 400、版本过期 / 已开赛返回 409，响应体 data 均带当前状态。
     */
    @PostMapping("/{id}/start")
    public ApiResponse<StartCheckView> start(@PathVariable Long id,
                                             @RequestParam(required = false) Long expectedVersion) {
        return ApiResponse.success("球场可用性校验通过，赛事已开赛（应打球洞集已冻结）",
                tournamentStartService.start(id, expectedVersion));
    }

    /**
     * 读取赛事排行榜（收口前后同一只读入口，天然保持数据边界）：
     * 报名中 / 进行中 → 明确标注的临时结果（进行中按开赛冻结洞集口径）；已结束 → 唯一正式快照；
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
