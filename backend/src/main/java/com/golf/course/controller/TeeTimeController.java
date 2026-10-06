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
import com.golf.course.entity.TeeTime;
import com.golf.course.service.TeeTimeService;

@RestController
@RequestMapping("/api/tee-times")
public class TeeTimeController {

    private final TeeTimeService teeTimeService;

    public TeeTimeController(TeeTimeService teeTimeService) {
        this.teeTimeService = teeTimeService;
    }

    @GetMapping
    public ApiResponse<List<TeeTime>> list(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) String date) {
        return ApiResponse.success(teeTimeService.list(status, date));
    }

    @GetMapping("/{id}")
    public ApiResponse<TeeTime> get(@PathVariable Long id) {
        return ApiResponse.success(teeTimeService.get(id));
    }

    /** 预订开球时间，默认状态 已订 */
    @PostMapping
    public ApiResponse<TeeTime> book(@RequestBody TeeTime input) {
        return ApiResponse.success("预订成功", teeTimeService.book(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<TeeTime> update(@PathVariable Long id, @RequestBody TeeTime input) {
        return ApiResponse.success("已更新", teeTimeService.update(id, input));
    }

    /** 释放时段：已订 → 可订 */
    @PostMapping("/{id}/release")
    public ApiResponse<TeeTime> release(@PathVariable Long id) {
        return ApiResponse.success("时段已释放", teeTimeService.release(id));
    }
}
