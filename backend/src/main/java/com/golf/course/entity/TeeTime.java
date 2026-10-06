package com.golf.course.entity;

import java.time.LocalDate;
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

import lombok.Getter;
import lombok.Setter;

/**
 * 开球时间：某天某个开球时段（slot，如 07:00）的可预订档位。
 *
 * <p>同一球员在同一天、同一时段不可重复预订（唯一约束 player_id + play_date + slot）。
 * 预订后状态由「可订」转为「已订」。</p>
 */
@Entity
@Table(name = "tee_time", uniqueConstraints = {
    @jakarta.persistence.UniqueConstraint(name = "uk_tee_player_slot",
            columnNames = {"player_id", "play_date", "slot"})
})
@Getter
@Setter
public class TeeTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 打球日期 */
    @Column(name = "play_date", nullable = false)
    private LocalDate playDate;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    /** 开球时段，如 07:00 / 13:30 */
    @Column(name = "slot", nullable = false, length = 8)
    private String slot;

    /** 可订 / 已订 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 派生：球员姓名，便于前端直接展示 */
    public String getPlayerName() {
        return player == null ? null : player.getName();
    }

    /** 派生：球员编号 */
    public String getPlayerNo() {
        return player == null ? null : player.getPlayerNo();
    }
}
