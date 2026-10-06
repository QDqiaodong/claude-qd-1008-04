package com.golf.course.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.TournamentLeaderboard;

public interface TournamentLeaderboardRepository extends JpaRepository<TournamentLeaderboard, Long> {

    List<TournamentLeaderboard> findByTournamentId(Long tournamentId);
}
