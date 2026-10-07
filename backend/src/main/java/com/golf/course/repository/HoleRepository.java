package com.golf.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.Hole;

public interface HoleRepository extends JpaRepository<Hole, Long> {

    /** 全库按状态查询（兼容未绑定球场的历史口径） */
    List<Hole> findByStatus(String status);

    List<Hole> findByPar(Integer par);

    /** 全库按洞号查询（兼容历史调用；新逻辑应使用 {@link #findByCourseIdAndHoleNo}） */
    Optional<Hole> findByHoleNo(Integer holeNo);

    // ---------------- 球场维度（开赛校验 / 记分卡 / 排行榜统一按球场取洞） ----------------

    List<Hole> findByCourseId(Long courseId);

    List<Hole> findByCourseIdOrderByHoleNoAsc(Long courseId);

    List<Hole> findByCourseIdAndStatus(Long courseId, String status);

    Optional<Hole> findByCourseIdAndHoleNo(Long courseId, Integer holeNo);

    boolean existsByCourseIdAndHoleNo(Long courseId, Integer holeNo);
}
