package com.golf.course.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.Setter;

/**
 * 赛事排行榜：并发演示的「共享实体」。
 *
 * <p>带 {@code @Version Long version}，用于乐观锁演示。Service 在「锁定并读取」时
 * 通过 {@code entityManager.lock(entity, LockModeType.OPTIMISTIC_FORCE_INCREMENT)} 强制版本号 +1，
 * 从而在并发预订场景下让后到的写操作因版本冲突而失败，避免竞态。</p>
 */
@Entity
@Table(name = "leaderboard")
@Getter
@Setter
public class TournamentLeaderboard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tournament_id", nullable = false)
    private Tournament tournament;

    /** 轮次，如第 1 轮 / 第 2 轮 */
    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    /** 领先球员姓名 */
    @Column(name = "leader_name", length = 48)
    private String leaderName;

    /** 领先球员总杆数 */
    @Column(name = "total_score")
    private Integer totalScore;

    /** 乐观锁版本号：每次 OPTIMISTIC_FORCE_INCREMENT 读取后 +1 */
    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 派生：赛事名称 */
    public String getTournamentName() {
        return tournament == null ? null : tournament.getName();
    }
}
