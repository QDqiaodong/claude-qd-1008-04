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
import com.golf.course.dto.EntryRequest;
import com.golf.course.entity.TournamentEntry;
import com.golf.course.service.TournamentEntryService;

import jakarta.validation.Valid;

/**
 * 赛事参赛名单：签核闭环的「全部参赛球员」范围来源。
 */
@RestController
@RequestMapping("/api/tournaments/{tournamentId}/entries")
public class TournamentEntryController {

    private final TournamentEntryService entryService;

    public TournamentEntryController(TournamentEntryService entryService) {
        this.entryService = entryService;
    }

    @GetMapping
    public ApiResponse<List<TournamentEntry>> list(@PathVariable Long tournamentId) {
        return ApiResponse.success(entryService.list(tournamentId));
    }

    @PostMapping
    public ApiResponse<TournamentEntry> add(@PathVariable Long tournamentId,
                                            @Valid @RequestBody EntryRequest req) {
        return ApiResponse.success("参赛球员已登记", entryService.add(tournamentId, req));
    }
}
