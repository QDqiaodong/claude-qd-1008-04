package com.golf.course.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.golf.course.common.BizException;
import com.golf.course.common.Dict;
import com.golf.course.entity.Course;
import com.golf.course.repository.CourseRepository;

/**
 * 球场业务：赛事与球洞的归属。球场编号唯一；应打洞数须为 1 ~ 18。
 */
@Service
public class CourseService {

    private final CourseRepository courseRepository;

    public CourseService(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    public List<Course> list() {
        return courseRepository.findAll();
    }

    public Course get(Long id) {
        return courseRepository.findById(id)
                .orElseThrow(() -> new BizException("球场不存在：" + id));
    }

    @Transactional
    public Course create(Course input) {
        if (input.getCourseNo() == null || input.getCourseNo().isBlank()) {
            throw new BizException("球场编号不能为空");
        }
        if (courseRepository.findByCourseNo(input.getCourseNo().trim()).isPresent()) {
            throw new BizException("球场编号已存在：" + input.getCourseNo());
        }
        if (input.getName() == null || input.getName().isBlank()) {
            throw new BizException("球场名称不能为空");
        }
        if (!Dict.isValidHoleCount(input.getHoleCount())) {
            throw new BizException("球场应打洞数须为 " + Dict.HOLE_COUNT_MIN + " ~ "
                    + Dict.HOLE_COUNT_MAX + "，当前：" + input.getHoleCount());
        }
        Course c = new Course();
        c.setCourseNo(input.getCourseNo().trim());
        c.setName(input.getName().trim());
        c.setHoleCount(input.getHoleCount());
        c.setNote(input.getNote());
        c.setCreatedAt(LocalDateTime.now());
        return courseRepository.save(c);
    }

    @Transactional
    public Course update(Long id, Course input) {
        Course c = get(id);
        if (input.getName() != null && !input.getName().isBlank()) {
            c.setName(input.getName().trim());
        }
        if (input.getHoleCount() != null) {
            if (!Dict.isValidHoleCount(input.getHoleCount())) {
                throw new BizException("球场应打洞数须为 " + Dict.HOLE_COUNT_MIN + " ~ "
                        + Dict.HOLE_COUNT_MAX + "，当前：" + input.getHoleCount());
            }
            c.setHoleCount(input.getHoleCount());
        }
        if (input.getNote() != null) {
            c.setNote(input.getNote());
        }
        return courseRepository.save(c);
    }
}
