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
 * 球场业务：赛事绑定的开赛场地基础信息。
 *
 * <p>核心规则：① 球场编号唯一；② 应配置洞数须落在 1 ~ 18；
 * ③ 球场被赛事绑定后只允许修改名称 / 备注，应打洞数不得随意改动（避免让已排定赛事的应打洞集合漂移），
 * 如需调整洞数应新建球场版本。</p>
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
        return courseRepository.findById(id).orElseThrow(() -> new BizException("球场不存在：" + id));
    }

    @Transactional
    public Course create(Course input) {
        if (input.getCourseNo() == null || input.getCourseNo().isBlank()) {
            throw new BizException("球场编号不能为空");
        }
        courseRepository.findByCourseNo(input.getCourseNo().trim()).ifPresent(c -> {
            throw new BizException("球场编号已存在：" + input.getCourseNo());
        });
        if (input.getName() == null || input.getName().isBlank()) {
            throw new BizException("球场名称不能为空");
        }
        if (!Dict.isValidCourseHoleCount(input.getHoleCount())) {
            throw new BizException("应配置洞数须为 " + Dict.COURSE_HOLE_MIN + " ~ "
                    + Dict.COURSE_HOLE_MAX + " 的整数，当前：" + input.getHoleCount());
        }
        Course c = new Course();
        c.setCourseNo(input.getCourseNo().trim());
        c.setName(input.getName().trim());
        c.setHoleCount(input.getHoleCount());
        c.setNote(input.getNote());
        c.setCreatedAt(LocalDateTime.now());
        return courseRepository.save(c);
    }

    /**
     * 维护球场。已被赛事使用的球场不允许改洞数（会改变这些赛事的应打洞集合），
     * 只允许改名称 / 备注；确需不同洞数请新建球场并在报名阶段改绑赛事。
     */
    @Transactional
    public Course update(Long id, Course input, Long expectedVersion) {
        Course c = get(id);
        if (expectedVersion != null && !expectedVersion.equals(c.getVersion())) {
            throw new com.golf.course.common.ConflictException("球场数据已变化：你打开的是 v"
                    + expectedVersion + "，当前已是 v" + c.getVersion()
                    + "。本次修改未生效，请刷新后重试");
        }
        if (input.getName() != null && !input.getName().isBlank()) {
            c.setName(input.getName().trim());
        }
        if (input.getNote() != null) {
            c.setNote(input.getNote());
        }
        if (input.getHoleCount() != null && !input.getHoleCount().equals(c.getHoleCount())) {
            throw new BizException("球场「" + c.getName() + "」已被赛事使用，应配置洞数（"
                    + c.getHoleCount() + " 洞）不能修改；如需不同洞数请新建球场并在报名阶段改绑赛事");
        }
        return courseRepository.save(c);
    }
}
