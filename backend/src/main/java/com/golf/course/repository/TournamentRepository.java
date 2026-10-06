package com.golf.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.golf.course.entity.Tournament;

import jakarta.persistence.LockModeType;

public interface TournamentRepository extends JpaRepository<Tournament, Long> {

    Optional<Tournament> findByTournamentNo(String tournamentNo);

    /**
     * 行级悲观写锁：赛事收口（推进「已结束」+ 生成正式排行榜快照）与已结束后的成绩补录
     * 共用这把锁，保证「校验成绩 → 冻结快照 → 翻状态」对同一场赛事串行化，
     * 多人同时收口时只有一份正式快照可以生成。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tournament t where t.id = :id")
    Optional<Tournament> findWithLockById(Long id);

    /** 缺球场难度参数（courseRating / slopeRating 任一缺失）的赛事——差点计算的明确缺项 */
    @Query("select t from Tournament t where t.courseRating is null or t.slopeRating is null "
            + "order by t.endDate, t.id")
    List<Tournament> findMissingDifficulty();
}
