package com.golf.course.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.HandicapHistoryRound;

public interface HandicapHistoryRoundRepository extends JpaRepository<HandicapHistoryRound, Long> {

    List<HandicapHistoryRound> findByHistoryIdOrderByIdAsc(Long historyId);
}
