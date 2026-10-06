package com.golf.course.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.HandicapRun;

public interface HandicapRunRepository extends JpaRepository<HandicapRun, Long> {

    List<HandicapRun> findAllByOrderByIdDesc();

    boolean existsByRuleId(Long ruleId);
}
