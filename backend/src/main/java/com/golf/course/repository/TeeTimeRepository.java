package com.golf.course.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.TeeTime;

public interface TeeTimeRepository extends JpaRepository<TeeTime, Long> {

    List<TeeTime> findByPlayDate(LocalDate playDate);

    List<TeeTime> findByStatus(String status);

    List<TeeTime> findByPlayerId(Long playerId);

    /** 判定同一球员同一天同一时段是否已存在预订（防止重复预订竞态） */
    List<TeeTime> findByPlayerIdAndPlayDateAndSlot(Long playerId, LocalDate playDate, String slot);
}
