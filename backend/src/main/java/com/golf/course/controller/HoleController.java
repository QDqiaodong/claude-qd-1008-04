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
    public ApiResponse<List<Hole>> list(@RequestParam(required = false) Integer par,
                                        @RequestParam(required = false) String status) {
        return ApiResponse.success(holeService.list(par, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<Hole> get(@PathVariable Long id) {
        return ApiResponse.success(holeService.get(id));
    }

    @PostMapping
    public ApiResponse<Hole> create(@RequestBody Hole input) {
        return ApiResponse.success("球洞已建档", holeService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<Hole> update(@PathVariable Long id, @RequestBody Hole input) {
        return ApiResponse.success("已更新", holeService.update(id, input));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<Hole> updateStatus(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("状态已更新", holeService.updateStatus(id, target));
    }
}
