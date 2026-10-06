package com.golf.course.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.golf.course.common.ApiResponse;
import com.golf.course.entity.Player;
import com.golf.course.service.PlayerService;

@RestController
@RequestMapping("/api/players")
public class PlayerController {

    private final PlayerService playerService;

    public PlayerController(PlayerService playerService) {
        this.playerService = playerService;
    }

    @GetMapping
    public ApiResponse<List<Player>> list() {
        return ApiResponse.success(playerService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Player> get(@PathVariable Long id) {
        return ApiResponse.success(playerService.get(id));
    }

    @PostMapping
    public ApiResponse<Player> create(@RequestBody Player input) {
        return ApiResponse.success("球员已建档", playerService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<Player> update(@PathVariable Long id, @RequestBody Player input) {
        return ApiResponse.success("已更新", playerService.update(id, input));
    }
}
