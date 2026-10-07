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
 * 赛事本轮球洞冻结清单：赛事开赛（报名中 → 进行中）校验通过的同一事务内，
 * 把当时「开放且标准杆、码距均合法」的应打球洞逐洞快照写入本表。
 *
 * <p>这是「开赛已进入进行中的赛事不能因为后来把球洞改为维护 / 封闭而静默改变本轮状态」的落点：
 * 进行中 / 已结束赛事的应打洞集合、每洞标准杆与码距一律读本表冻结值，
 * 不再回查 hole 表实时状态；但后续<b>新赛事</b>开赛仍必须重新做完整校验。</p>
 *
 * <p>一场赛事 × 一个洞号唯一（uk_round_hole_tour_hole 兜底，并发开赛也不可能写入两份）。</p>
 */
@Entity
@Table(name = "tournament_round_hole", uniqueConstraints = {
        @UniqueConstraint(name = "uk_round_hole_tour_hole", columnNames = {"tournament_id", "hole_no"})
})
@Getter
@Setter
public class TournamentRoundHole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tournament_id", nullable = false)
    private Tournament tournament;

    /** 冻结时引用的球洞（仅留痕；判定一律以本实体字段为准） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hole_id", nullable = false)
    private Hole hole;

    /** 洞号（冻结值） */
    @Column(name = "hole_no", nullable = false)
    private Integer holeNo;

    /** 开赛瞬间标准杆（冻结值，之后维护球洞不影响本轮） */
    @Column(name = "par", nullable = false)
    private Integer par;

    /** 开赛瞬间码距（冻结值） */
    @Column(name = "yardage", nullable = false)
    private Integer yardage;

    /** 开赛瞬间球洞版本号（留痕：说明本清单基于哪个洞版本冻结） */
    @Column(name = "hole_version", nullable = false)
    private Long holeVersion;

    @Column(name = "frozen_at", nullable = false)
    private LocalDateTime frozenAt;
}
