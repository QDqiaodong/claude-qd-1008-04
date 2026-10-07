package com.golf.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.golf.course.entity.Hole;

import jakarta.persistence.LockModeType;

public interface HoleRepository extends JpaRepository<Hole, Long> {

    List<Hole> findByStatus(String status);

    List<Hole> findByPar(Integer par);

    Optional<Hole> findByHoleNo(Integer holeNo);

    /** 某球场的全部球洞（按洞号升序） */
    List<Hole> findByCourseIdOrderByHoleNoAsc(Long courseId);

    /** 某球场内按洞号查找（同球场洞号唯一） */
    Optional<Hole> findByCourseIdAndHoleNo(Long courseId, Integer holeNo);

    /**
     * 行级悲观写锁：开赛校验在赛事行锁之后再对涉及球洞加锁，
     * 保证「读最新洞版本 → 校验 → 冻结应打洞 → 翻赛事状态」期间，
     * 没有人能并发把同一洞改成维护 / 封闭；后到的维护操作等待并在提交时按版本冲突处理。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Hole h where h.id = :id")
    Optional<Hole> findWithLockById(Long id);
}
