package com.golf.course.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.golf.course.entity.Scorecard;

public interface ScorecardRepository extends JpaRepository<Scorecard, Long> {

    List<Scorecard> findByTournamentIdOrderByRoundNoAscIdAsc(Long tournamentId);

    List<Scorecard> findByPlayerIdOrderByUpdatedAtDesc(Long playerId);

    Optional<Scorecard> findByTournamentIdAndPlayerIdAndRoundNo(Long tournamentId, Long playerId, Integer roundNo);

    List<Scorecard> findByTournamentIdAndPlayerId(Long tournamentId, Long playerId);

    long countByTournamentIdAndStatus(Long tournamentId, String status);

    /**
     * 差点重算·单球员取轮次：走 (player_id, status) 索引，只取该球员的已签核卡，
     * 绝不为了算一个球员去扫描全部成绩。
     */
    @Query("select sc from Scorecard sc join fetch sc.tournament "
            + "where sc.player.id = :playerId and sc.status = :status order by sc.id desc")
    List<Scorecard> findByPlayerIdAndStatusWithTournament(@Param("playerId") Long playerId,
                                                          @Param("status") String status);

    /**
     * 差点重算·按赛事日期分批扫描：一批赛事一次 IN 查询取回全部已签核卡
     * （join fetch 避免逐卡回表），批次之间不重复扫描。
     */
    @Query("select sc from Scorecard sc join fetch sc.tournament join fetch sc.player "
            + "where sc.status = :status and sc.tournament.id in :tournamentIds order by sc.id")
    List<Scorecard> findByStatusAndTournamentIdIn(@Param("status") String status,
                                                  @Param("tournamentIds") Collection<Long> tournamentIds);
}
