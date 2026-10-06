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

import lombok.Getter;
import lombok.Setter;

/**
 * 成绩：某球员在某赛事某洞的实际杆数，用于与标准杆对比。
 *
 * <p>strokes 单洞杆数须 ≥ 1；与所属洞的 par 对比得到 +N / -N / E 的相对成绩。</p>
 */
@Entity
@Table(name = "score")
@Getter
@Setter
public class Score {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tournament_id", nullable = false)
    private Tournament tournament;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "hole_id", nullable = false)
    private Hole hole;

    /** 轮次 */
    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    /** 本洞实际杆数，须 ≥ 1 */
    @Column(name = "strokes", nullable = false)
    private Integer strokes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 派生：球员姓名 */
    public String getPlayerName() {
        return player == null ? null : player.getName();
    }

    /** 派生：洞号 */
    public Integer getHoleNo() {
        return hole == null ? null : hole.getHoleNo();
    }

    /** 派生：标准杆 */
    public Integer getPar() {
        return hole == null ? null : hole.getPar();
    }

    /** 派生：相对标准杆的差距（杆数 - par），负为好 */
    public Integer getToPar() {
        if (hole == null || hole.getPar() == null || strokes == null) {
            return null;
        }
        return strokes - hole.getPar();
    }
}
