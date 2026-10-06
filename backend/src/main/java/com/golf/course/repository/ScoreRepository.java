package com.golf.course.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.golf.course.entity.Score;

public interface ScoreRepository extends JpaRepository<Score, Long> {

    List<Score> findByTournamentId(Long tournamentId);

    List<Score> findByTournamentIdAndPlayerId(Long tournamentId, Long playerId);

    List<Score> findByTournamentIdAndHoleId(Long tournamentId, Long holeId);

    /**
     * 赛事全部成绩的原始投影（不走实体关联导航）。
     *
     * <p>收口校验必须能发现「成绩指向已不存在球员 / 球洞」的孤儿数据——
     * {@link Score} 对 player / hole 是 EAGER 关联，导航查询会把这种行直接吞掉。
     * 排行榜收口只认本投影返回的原始 ID 与杆数。</p>
     */
    @Query(value = "SELECT s.player_id AS playerId, s.hole_id AS holeId, "
            + "s.round_no AS roundNo, s.strokes AS strokes "
            + "FROM score s WHERE s.tournament_id = :tournamentId "
            + "ORDER BY s.player_id, s.round_no, s.hole_id, s.id", nativeQuery = true)
    List<ScoreRow> findRowsByTournamentId(Long tournamentId);

    /** score 表原始行投影（含可能已不存在的 player / hole 外键值）。 */
    interface ScoreRow {
        Long getPlayerId();

        Long getHoleId();

        Integer getRoundNo();

        Integer getStrokes();
    }
}
