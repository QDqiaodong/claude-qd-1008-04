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
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.Setter;

/**
 * 赛事参赛名单：界定一场赛事「全部参赛球员」的范围与应打轮次数。
 *
 * <p>同赛事同球员唯一（DB 唯一约束兜底）。赛事从进行中推进到已结束前，
 * 每个参赛名额在每个应打轮次都必须有一张「已签核」记分卡，否则阻止收口。</p>
 */
@Entity
@Table(name = "tournament_entry", uniqueConstraints = {
        @UniqueConstraint(name = "uk_entry_tour_player", columnNames = {"tournament_id", "player_id"})
})
@Getter
@Setter
public class TournamentEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tournament_id", nullable = false)
    private Tournament tournament;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    /** 应打轮次数（≥1） */
    @Column(name = "round_count", nullable = false)
    private Integer roundCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public String getPlayerName() {
        return player == null ? null : player.getName();
    }

    public String getPlayerNo() {
        return player == null ? null : player.getPlayerNo();
    }

    public String getTournamentName() {
        return tournament == null ? null : tournament.getName();
    }
}
