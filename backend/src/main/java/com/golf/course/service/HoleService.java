package com.golf.course.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.golf.course.common.BizException;
import com.golf.course.common.Dict;
import com.golf.course.entity.Hole;
import com.golf.course.repository.HoleRepository;

/**
 * 球洞业务。核心规则：① 洞号唯一；② 标准杆只能是 3 / 4 / 5；③ 状态须为开放 / 维护 / 封闭。
 */
@Service
public class HoleService {

    private final HoleRepository holeRepository;

    public HoleService(HoleRepository holeRepository) {
        this.holeRepository = holeRepository;
    }

    public List<Hole> list(Integer par, String status) {
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
        if (input.getHoleNo() == null) {
            throw new BizException("洞号不能为空");
        }
        if (holeRepository.findByHoleNo(input.getHoleNo()).isPresent()) {
            throw new BizException("洞号已存在：" + input.getHoleNo());
        }
        if (input.getPar() == null || input.getPar() < 3 || input.getPar() > 5) {
            throw new BizException("标准杆必须落在 3 / 4 / 5 之间，当前：" + input.getPar());
        }
        if (input.getYardage() == null || input.getYardage() <= 0) {
            throw new BizException("码距必须大于 0");
        }
        if (!Dict.isValidHoleStatus(input.getStatus())) {
            throw new BizException("球洞状态不合法：" + input.getStatus());
        }
        Hole hole = new Hole();
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
        if (input.getPar() != null) {
            if (input.getPar() < 3 || input.getPar() > 5) {
                throw new BizException("标准杆必须落在 3 / 4 / 5 之间，当前：" + input.getPar());
            }
            hole.setPar(input.getPar());
        }
        if (input.getYardage() != null) {
            if (input.getYardage() <= 0) {
                throw new BizException("码距必须大于 0");
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
    public Hole updateStatus(Long id, String target) {
        Hole hole = get(id);
        if (!Dict.isValidHoleStatus(target)) {
            throw new BizException("球洞状态不合法：" + target);
        }
        hole.setStatus(target);
        return holeRepository.save(hole);
    }
}
