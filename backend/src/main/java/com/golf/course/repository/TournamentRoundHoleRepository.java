package com.golf.course.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.TournamentRoundHole;

public interface TournamentRoundHoleRepository extends JpaRepository<TournamentRoundHole, Long> {

    /** 开赛冻结的本轮应打球洞（按洞号升序）；冻结清单存在即代表赛事已开赛 */
    List<TournamentRoundHole> findByTournamentIdOrderByHoleNoAsc(Long tournamentId);
}
