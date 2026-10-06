package com.golf.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.golf.course.entity.HandicapHistory;

public interface HandicapHistoryRepository extends JpaRepository<HandicapHistory, Long> {

    /** 球员最近一次计算（任意状态） */
    Optional<HandicapHistory> findTopByPlayerIdOrderByIdDesc(Long playerId);

    /** 球员最近一次「已计算」——作为下一次计算的 previousIndex 与下调限制基准 */
    Optional<HandicapHistory> findTopByPlayerIdAndStatusOrderByIdDesc(Long playerId, String status);

    List<HandicapHistory> findByPlayerIdOrderByIdDesc(Long playerId);

    List<HandicapHistory> findByRunIdOrderByIdAsc(Long runId);

    boolean existsByRuleId(Long ruleId);

    /** 每个球员最新一条计算（球员列表页汇总用，一条 SQL 取回，不逐球员查） */
    @Query("select h from HandicapHistory h join fetch h.player "
            + "where h.id in (select max(x.id) from HandicapHistory x group by x.player.id)")
    List<HandicapHistory> findLatestPerPlayer();
}
