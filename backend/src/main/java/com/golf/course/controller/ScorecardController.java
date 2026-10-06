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
import com.golf.course.dto.ConfirmCardRequest;
import com.golf.course.dto.DisputeRequest;
import com.golf.course.dto.EventView;
import com.golf.course.dto.OfficialResult;
import com.golf.course.dto.RulingRequest;
import com.golf.course.dto.SaveHoleRequest;
import com.golf.course.dto.ScorecardView;
import com.golf.course.dto.SignoffCheck;
import com.golf.course.dto.SubmitCardRequest;
import com.golf.course.entity.Scorecard;
import com.golf.course.service.ScorecardService;

import jakarta.validation.Valid;

/**
 * 赛事记分卡签核闭环接口。
 *
 * <p>角色入口（后端各自做状态/归属校验，前端入口仅为便利）：
 * <ul>
 *   <li>记分员：{@code POST /draft} 取/建草稿、{@code POST /holes} 录草稿洞、{@code POST /submit} 提交；</li>
 *   <li>球员：{@code GET /mine}、{@code POST /confirm}、{@code POST /dispute}；</li>
 *   <li>裁判：{@code GET /disputed}、{@code POST /rule}；</li>
 *   <li>收口/正式成绩：{@code GET /official}、{@code GET /signoff-check}、{@code GET /{id}/events}。</li>
 * </ul>
 * 所有写操作都要带 expectedVersion，过期一律 409。</p>
 */
@RestController
@RequestMapping("/api/scorecards")
public class ScorecardController {

    private final ScorecardService scorecardService;

    public ScorecardController(ScorecardService scorecardService) {
        this.scorecardService = scorecardService;
    }

    // ---------------- 记分员 ----------------

    /** 按赛事列出全部记分卡（记分员工作台） */
    @GetMapping
    public ApiResponse<List<Scorecard>> list(@RequestParam Long tournamentId) {
        return ApiResponse.success(scorecardService.listByTournament(tournamentId));
    }

    /** 按 赛事×轮次×球员 取草稿，不存在且在参赛名单内时自动创建 */
    @PostMapping("/draft")
    public ApiResponse<ScorecardView> draft(@RequestParam Long tournamentId,
                                            @RequestParam Long playerId,
                                            @RequestParam Integer roundNo) {
        Scorecard card = scorecardService.getOrCreateDraft(tournamentId, playerId, roundNo);
        return ApiResponse.success(scorecardService.getView(card.getId()));
    }

    @PostMapping("/{id}/holes")
    public ApiResponse<ScorecardView> saveHole(@PathVariable Long id,
                                               @Valid @RequestBody SaveHoleRequest req) {
        return ApiResponse.success("草稿已保存", scorecardService.saveHole(id, req));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<ScorecardView> submit(@PathVariable Long id,
                                             @Valid @RequestBody SubmitCardRequest req) {
        return ApiResponse.success("记分卡已提交，等待球员确认", scorecardService.submit(id, req));
    }

    // ---------------- 球员 ----------------

    /** 球员只查自己的记分卡 */
    @GetMapping("/mine")
    public ApiResponse<List<Scorecard>> mine(@RequestParam Long playerId) {
        return ApiResponse.success(scorecardService.listMine(playerId));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<ScorecardView> confirm(@PathVariable Long id,
                                              @Valid @RequestBody ConfirmCardRequest req) {
        return ApiResponse.success("记分卡已正式签核", scorecardService.confirm(id, req));
    }

    @PostMapping("/{id}/dispute")
    public ApiResponse<ScorecardView> dispute(@PathVariable Long id,
                                              @Valid @RequestBody DisputeRequest req) {
        return ApiResponse.success("争议已提交，转裁判处理", scorecardService.dispute(id, req));
    }

    // ---------------- 裁判 ----------------

    /** 裁判只看争议中 */
    @GetMapping("/disputed")
    public ApiResponse<List<Scorecard>> disputed(@RequestParam(required = false) Long tournamentId) {
        return ApiResponse.success(scorecardService.listDisputed(tournamentId));
    }

    @PostMapping("/{id}/rule")
    public ApiResponse<ScorecardView> rule(@PathVariable Long id,
                                           @Valid @RequestBody RulingRequest req) {
        return ApiResponse.success("裁决完成，记分卡发回球员确认", scorecardService.rule(id, req));
    }

    // ---------------- 详情 / 留痕 / 正式成绩 / 收口 ----------------

    @GetMapping("/{id}")
    public ApiResponse<ScorecardView> get(@PathVariable Long id) {
        return ApiResponse.success(scorecardService.getView(id));
    }

    @GetMapping("/{id}/events")
    public ApiResponse<List<EventView>> events(@PathVariable Long id) {
        return ApiResponse.success(scorecardService.events(id));
    }

    /** 正式排行榜：只含已签核卡，争议中/待确认/草稿一律缺席 */
    @GetMapping("/official")
    public ApiResponse<List<OfficialResult>> official(@RequestParam Long tournamentId) {
        return ApiResponse.success(scorecardService.officialResults(tournamentId));
    }

    /** 赛事收口前的签核检查：列出所有阻止结束的原因 */
    @GetMapping("/signoff-check")
    public ApiResponse<SignoffCheck> signoffCheck(@RequestParam Long tournamentId) {
        return ApiResponse.success(scorecardService.signoffCheck(tournamentId));
    }
}
