package com.golf.course.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.HandicapRule;

public interface HandicapRuleRepository extends JpaRepository<HandicapRule, Long> {

    /** 计算基准日（含）之前已生效的最新一条规则 */
    Optional<HandicapRule> findTopByEffectiveDateLessThanEqualOrderByEffectiveDateDesc(LocalDate date);

    Optional<HandicapRule> findByEffectiveDate(LocalDate effectiveDate);

    List<HandicapRule> findAllByOrderByEffectiveDateDescIdDesc();
}
