package com.golf.course.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.TournamentStartCheck;

public interface TournamentStartCheckRepository extends JpaRepository<TournamentStartCheck, Long> {

    Optional<TournamentStartCheck> findByTournamentId(Long tournamentId);
}
