package com.golf.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.ScorecardHole;

public interface ScorecardHoleRepository extends JpaRepository<ScorecardHole, Long> {

    List<ScorecardHole> findByScorecardIdOrderByHoleNoAsc(Long scorecardId);

    Optional<ScorecardHole> findByScorecardIdAndHoleNo(Long scorecardId, Integer holeNo);

    void deleteByScorecardId(Long scorecardId);
}
