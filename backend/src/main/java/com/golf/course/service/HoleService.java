package com.golf.course.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.golf.course.common.BizException;
import com.golf.course.common.ConflictException;
import com.golf.course.common.Dict;
import com.golf.course.entity.Course;
import com.golf.course.entity.Hole;
import com.golf.course.repository.CourseRepository;
import com.golf.course.repository.HoleRepository;

/**
 * 球洞业务。核心规则：
 * <ol>
 *   <li>球洞必须归属某个球场，同球场内洞号唯一（不同球场可以各有 1 ~ 18 洞）；</li>
 *   <li>标准杆只能是 3 / 4 / 5，码距须为 1 ~ 800 的整数；</li>
 *   <li>状态须为开放 / 维护 / 封闭；</li>
 *   <li>所有写操作都带乐观版本校验：两个管理员同时维护同一球洞时，
 *       只有基于最新洞版本的一次提交成功，过期一方收到 409「数据已变化」并回读当前洞状态。</li>
 * </ol>
 */
@Service
public class HoleService {

    private final HoleRepository holeRepository;
    private final CourseRepository courseRepository;

    public HoleService(HoleRepository holeRepository, CourseRepository courseRepository) {
        this.holeRepository = holeRepository;
        this.courseRepository = courseRepository;
    }

    /** 列表支持按球场 / 标准杆 / 状态过滤；不带条件返回全部球场的球洞（带球场名便于页面区分）。 */
    public List<Hole> list(Long courseId, Integer par, String status) {
        if (courseId != null) {
            List<Hole> holes = holeRepository.findByCourseIdOrderByHoleNoAsc(courseId);
            return holes.stream()
                    .filter(h -> par == null || par.equals(h.getPar()))
                    .filter(h -> status == null || status.isBlank() || status.equals(h.getStatus()))
                    .toList();
        }
        if (par != null) {
            return holeRepository.findByPar(par);
        }
        if (status != null && !status.isBlank()) {
            return holeRepository.findByStatus(status);
        }
        return holeRepository.findAll();
    }

    public Hole get(Long id) {
        return holeRepository.findById(id).orElseThrow(() -> new BizException("球洞不存在：" + id));
    }

    @Transactional
    public Hole create(Hole input) {
        if (input.getCourse() == null || input.getCourse().getId() == null) {
            throw new BizException("必须指定球洞所属球场");
        }
        Course course = courseRepository.findById(input.getCourse().getId())
                .orElseThrow(() -> new BizException("球场不存在：" + input.getCourse().getId()));
        if (input.getHoleNo() == null) {
            throw new BizException("洞号不能为空");
        }
        if (input.getHoleNo() < 1 || input.getHoleNo() > Dict.COURSE_HOLE_MAX) {
            throw new BizException("洞号必须落在 1 ~ " + Dict.COURSE_HOLE_MAX + " 之间，当前：" + input.getHoleNo());
        }
        if (input.getHoleNo() > course.getHoleCount()) {
            throw new BizException("球场「" + course.getName() + "」只配置 " + course.getHoleCount()
                    + " 个洞，不能建立第 " + input.getHoleNo() + " 洞");
        }
        if (holeRepository.findByCourseIdAndHoleNo(course.getId(), input.getHoleNo()).isPresent()) {
            throw new BizException("球场「" + course.getName() + "」已存在第 " + input.getHoleNo() + " 洞");
        }
        validateBasics(input);
        if (!Dict.isValidHoleStatus(input.getStatus())) {
            throw new BizException("球洞状态不合法：" + input.getStatus());
        }
        Hole hole = new Hole();
        hole.setCourse(course);
        hole.setHoleNo(input.getHoleNo());
        hole.setPar(input.getPar());
        hole.setYardage(input.getYardage());
        hole.setStatus(input.getStatus());
        hole.setCreatedAt(LocalDateTime.now());
        return holeRepository.save(hole);
    }

    @Transactional
    public Hole update(Long id, Hole input, Long expectedVersion) {
        Hole hole = get(id);
        checkVersion(hole, expectedVersion);
        if (input.getPar() != null) {
            if (!Dict.isValidPar(input.getPar())) {
                throw new BizException("标准杆必须为 3 / 4 / 5，当前：" + input.getPar());
            }
            hole.setPar(input.getPar());
        }
        if (input.getYardage() != null) {
            if (!Dict.isValidYardage(input.getYardage())) {
                throw new BizException("码距必须为 " + Dict.YARDAGE_MIN + " ~ " + Dict.YARDAGE_MAX
                        + " 的整数，当前：" + input.getYardage());
            }
            hole.setYardage(input.getYardage());
        }
        if (input.getStatus() != null && !input.getStatus().isBlank()) {
            if (!Dict.isValidHoleStatus(input.getStatus())) {
                throw new BizException("球洞状态不合法：" + input.getStatus());
            }
            hole.setStatus(input.getStatus());
        }
        return holeRepository.save(hole);
    }

    @Transactional
    public Hole updateStatus(Long id, String target, Long expectedVersion) {
        Hole hole = get(id);
        checkVersion(hole, expectedVersion);
        if (!Dict.isValidHoleStatus(target)) {
            throw new BizException("球洞状态不合法：" + target);
        }
        hole.setStatus(target);
        return holeRepository.save(hole);
    }

    private void validateBasics(Hole input) {
        if (!Dict.isValidPar(input.getPar())) {
            throw new BizException("标准杆必须为 3 / 4 / 5，当前：" + input.getPar());
        }
        if (!Dict.isValidYardage(input.getYardage())) {
            throw new BizException("码距必须为 " + Dict.YARDAGE_MIN + " ~ " + Dict.YARDAGE_MAX
                    + " 的整数，当前：" + input.getYardage());
        }
    }

    /**
     * 球洞乐观版本闸门：页面必须基于最新洞版本维护。版本不一致直接 409，
     * 消息带当前洞状态与版本，前端据此提示「数据已变化」并重新拉取最新洞列表，
     * 杜绝两人同时维护同一洞时后到者静默覆盖。JPA {@code @Version} 在提交时还会再兜一层。
     */
    private void checkVersion(Hole hole, Long expectedVersion) {
        if (expectedVersion != null && !expectedVersion.equals(hole.getVersion())) {
            throw new ConflictException("球洞数据已变化：你打开的是 v" + expectedVersion
                    + "，第 " + hole.getHoleNo() + " 洞当前已是 v" + hole.getVersion()
                    + "（状态：" + hole.getStatus() + "，par " + hole.getPar() + "，"
                    + hole.getYardage() + " 码）。本次维护未生效，请重新读取最新球洞状态后再操作");
        }
    }
}
