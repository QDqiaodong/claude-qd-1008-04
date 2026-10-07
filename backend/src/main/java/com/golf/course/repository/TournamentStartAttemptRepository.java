package com.golf.course.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.TournamentStartAttempt;

public interface TournamentStartAttemptRepository extends JpaRepository<TournamentStartAttempt, Long> {

    List<TournamentStartAttempt> findByTournamentIdOrderByIdDesc(Long tournamentId);
}
