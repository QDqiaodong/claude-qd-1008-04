package com.golf.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.TournamentEntry;

public interface TournamentEntryRepository extends JpaRepository<TournamentEntry, Long> {

    List<TournamentEntry> findByTournamentIdOrderByIdAsc(Long tournamentId);

    Optional<TournamentEntry> findByTournamentIdAndPlayerId(Long tournamentId, Long playerId);

    boolean existsByTournamentIdAndPlayerId(Long tournamentId, Long playerId);
}
