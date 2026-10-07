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
import com.golf.course.entity.Hole;
import com.golf.course.service.HoleService;

@RestController
@RequestMapping("/api/holes")
public class HoleController {

    private final HoleService holeService;

    public HoleController(HoleService holeService) {
        this.holeService = holeService;
    }

    @GetMapping
    public ApiResponse<List<Hole>> list(@RequestParam(required = false) Long courseId,
                                        @RequestParam(required = false) Integer par,
                                        @RequestParam(required = false) String status) {
        return ApiResponse.success(holeService.list(courseId, par, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<Hole> get(@PathVariable Long id) {
        return ApiResponse.success(holeService.get(id));
    }

    @PostMapping
    public ApiResponse<Hole> create(@RequestBody Hole input) {
        return ApiResponse.success("球洞已建档", holeService.create(input));
    }

    /**
     * 维护球洞 par / 码距 / 状态。body 中可带 version 做乐观并发闸门；
     * 漏传时仍由 JPA @Version 在提交期兜底，并发维护同一球洞的过期一方收 409。
     */
    @PutMapping("/{id}")
    public ApiResponse<Hole> update(@PathVariable Long id, @RequestBody Hole input) {
        return ApiResponse.success("已更新", holeService.update(id, input));
    }

    /**
     * 维护球洞状态（开放 / 维护 / 封闭）。expectedVersion 为页面看到的球洞版本，
     * 过期返回 409「数据已变化」，防止两人并发维护同一球洞时静默覆盖。
     */
    @PostMapping("/{id}/status")
    public ApiResponse<Hole> updateStatus(@PathVariable Long id,
                                          @RequestParam String target,
                                          @RequestParam(required = false) Long expectedVersion) {
        return ApiResponse.success("状态已更新", holeService.updateStatus(id, target, expectedVersion));
    }
}
