package com.golf.course.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.ScorecardEvent;

public interface ScorecardEventRepository extends JpaRepository<ScorecardEvent, Long> {

    List<ScorecardEvent> findByScorecardIdOrderByIdAsc(Long scorecardId);
}
