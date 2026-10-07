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
import com.golf.course.entity.Course;
import com.golf.course.service.CourseService;

/**
 * 球场：赛事绑定与球洞归属。前端在「新建 / 编辑赛事」与「球洞建档」时拉取球场列表。
 */
@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @GetMapping
    public ApiResponse<List<Course>> list() {
        return ApiResponse.success(courseService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Course> get(@PathVariable Long id) {
        return ApiResponse.success(courseService.get(id));
    }

    @PostMapping
    public ApiResponse<Course> create(@RequestBody Course input) {
        return ApiResponse.success("球场已建档", courseService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<Course> update(@PathVariable Long id, @RequestBody Course input) {
        return ApiResponse.success("球场已更新", courseService.update(id, input));
    }
}
