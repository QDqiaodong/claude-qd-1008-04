package com.golf.course.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * 球员：下场打球与参加赛事的人员。
 *
 * <p>playerNo 唯一；handicap 差点取值范围 0 ~ 54（管理员手工维护）。</p>
 *
 * <p>handicapIndex 是「差点指数重算」写回的当前计算指数（样本不足时为 NULL）。
 * 它只是最近一次计算结果的冗余展示位——计算依据、采用轮次、规则快照与历史指数
 * 全部留痕在 handicap_history / handicap_history_round，绝不允许只改这个数字。</p>
 */
@Entity
@Table(name = "player")
@Getter
@Setter
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 球员编号，唯一 */
    @Column(name = "player_no", nullable = false, unique = true, length = 16)
    private String playerNo;

    @Column(name = "name", nullable = false, length = 48)
    private String name;

    /** 差点：0 ~ 54（管理员手工维护的基础差点） */
    @Column(name = "handicap", nullable = false)
    private Integer handicap;

    /** 当前计算差点指数（由重算流程写回；样本不足 / 从未计算时为 NULL） */
    @Column(name = "handicap_index", precision = 4, scale = 1)
    private BigDecimal handicapIndex;

    /** 最近一次差点指数计算时间 */
    @Column(name = "handicap_index_at")
    private LocalDateTime handicapIndexAt;

    @Column(name = "phone", length = 24)
    private String phone;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
