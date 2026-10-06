package com.golf.course.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.HandicapRunError;

public interface HandicapRunErrorRepository extends JpaRepository<HandicapRunError, Long> {

    List<HandicapRunError> findByRunIdOrderByIdAsc(Long runId);
}
