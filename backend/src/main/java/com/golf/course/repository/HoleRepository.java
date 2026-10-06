package com.golf.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.Hole;

public interface HoleRepository extends JpaRepository<Hole, Long> {

    List<Hole> findByStatus(String status);

    List<Hole> findByPar(Integer par);

    Optional<Hole> findByHoleNo(Integer holeNo);
}
