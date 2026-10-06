package com.golf.course.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.LeaderboardSnapshot;

public interface LeaderboardSnapshotRepository extends JpaRepository<LeaderboardSnapshot, Long> {

    Optional<LeaderboardSnapshot> findByTournamentId(Long tournamentId);
}
