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
 * 球洞业务。核心规则：① (球场, 洞号) 唯一；② 标准杆只能是 3 / 4 / 5；
 * ③ 码距须为 1 ~ 800 的正整数；④ 状态须为 开放 / 维护 / 封闭；⑤ 必须归属一个球场。
 *
 * <p>球洞带 JPA {@code @Version} 乐观锁：并发维护同一球洞（改 par / 码距 / 状态）时，
 * 只有基于最新版本的一次写成功；携带过期版本号的写操作收 409「数据已变化」。
 * 这与开赛校验的球洞悲观锁共同保证：并发开赛与并发维护不会互相读到旧版本。</p>
 */
@Service
public class HoleService {

    private final HoleRepository holeRepository;
    private final CourseRepository courseRepository;

    public HoleService(HoleRepository holeRepository, CourseRepository courseRepository) {
        this.holeRepository = holeRepository;
        this.courseRepository = courseRepository;
    }

    public List<Hole> list(Long courseId, Integer par, String status) {
        if (courseId != null) {
            List<Hole> all = holeRepository.findByCourseIdOrderByHoleNoAsc(courseId);
            return all.stream()
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
        if (holeRepository.findByCourseIdAndHoleNo(course.getId(), input.getHoleNo()).isPresent()) {
            throw new BizException("球场「" + course.getName() + "」已存在第 " + input.getHoleNo() + " 洞");
        }
        validateShape(input.getPar(), input.getYardage());
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
    public Hole update(Long id, Hole input) {
        Hole hole = get(id);
        if (input.getVersion() != null
                && !input.getVersion().equals(hole.getVersion())) {
            throw new ConflictException("球洞数据已变化：你打开的是 v" + input.getVersion()
                    + "，当前已是 v" + hole.getVersion() + "（第 " + hole.getHoleNo()
                    + " 洞，状态：" + hole.getStatus()
                    + "）。本次维护未生效，请重新读取该球洞最新状态后再操作");
        }
        if (input.getPar() != null) {
            if (!Dict.isValidPar(input.getPar())) {
                throw new BizException("标准杆必须落在 3 / 4 / 5 之间，当前：" + input.getPar());
            }
            hole.setPar(input.getPar());
        }
        if (input.getYardage() != null) {
            if (!Dict.isValidYardage(input.getYardage())) {
                throw new BizException("码距必须为 " + Dict.YARDAGE_MIN + " ~ " + Dict.YARDAGE_MAX
                        + " 的正整数，当前：" + input.getYardage());
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

    /**
     * 维护球洞状态（开放 / 维护 / 封闭）。expectedVersion 非空时做乐观并发闸门：
     * 两人同时维护同一球洞，过期一方收 409 并带回最新版本；不传则只依赖 @Version 提交期兜底。
     */
    @Transactional
    public Hole updateStatus(Long id, String target, Long expectedVersion) {
        Hole hole = get(id);
        if (!Dict.isValidHoleStatus(target)) {
            throw new BizException("球洞状态不合法：" + target);
        }
        if (expectedVersion != null && !expectedVersion.equals(hole.getVersion())) {
            throw new ConflictException("球洞数据已变化：你打开的是 v" + expectedVersion
                    + "，当前已是 v" + hole.getVersion() + "（第 " + hole.getHoleNo()
                    + " 洞，状态：" + hole.getStatus()
                    + "）。本次状态维护未生效，请重新读取最新球洞状态后再操作");
        }
        hole.setStatus(target);
        return holeRepository.save(hole);
    }

    private void validateShape(Integer par, Integer yardage) {
        if (!Dict.isValidPar(par)) {
            throw new BizException("标准杆必须落在 3 / 4 / 5 之间，当前：" + par);
        }
        if (!Dict.isValidYardage(yardage)) {
            throw new BizException("码距必须为 " + Dict.YARDAGE_MIN + " ~ " + Dict.YARDAGE_MAX
                    + " 的正整数，当前：" + yardage);
        }
    }
}
