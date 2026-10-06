package com.golf.course.entity;

import java.math.BigDecimal;
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
 * 差点指数计算历史：每次重算（批量或定向）为每个球员落一行完整计算依据。
 *
 * <p>历史行永不修改、永不删除——这是「历史差点按当时规则可追溯」的根基：
 * ruleJson 固化计算时点的规则参数快照，即使规则后来被新口径替代，
 * 本次计算的口径依然可还原；轮次级的采用 / 排除明细见 handicap_history_round。</p>
 *
 * <p>状态：已计算（index_value 有效）/ 样本不足（可计轮次 &lt; 规则最少轮次，index_value 为 NULL）。</p>
 */
@Entity
@Table(name = "handicap_history")
@Getter
@Setter
public class HandicapHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    /** 所属批量重算批次；定向 / 单球员重算为 NULL */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "run_id")
    private HandicapRun run;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "rule_id", nullable = false)
    private HandicapRule rule;

    /** 计算时点规则参数快照（JSON），保证规则后续被替代时历史口径仍可还原 */
    @Column(name = "rule_json", nullable = false, length = 512)
    private String ruleJson;

    /** 计算基准日：只取该日（含）之前已结束的轮次 */
    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    /** 已计算 / 样本不足 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 窗口内可计算差值的轮次数（缺参 / 快照损坏的轮次不计入） */
    @Column(name = "sample_size", nullable = false)
    private Integer sampleSize;

    /** 实际被采用（最低 K 个差值）的轮次数 */
    @Column(name = "used_count", nullable = false)
    private Integer usedCount;

    /** 计算出的差点指数（保留一位小数）；样本不足时为 NULL */
    @Column(name = "index_value", precision = 4, scale = 1)
    private BigDecimal indexValue;

    /** 上一次「已计算」的指数（用于展示最近变化与下调限制） */
    @Column(name = "previous_index", precision = 4, scale = 1)
    private BigDecimal previousIndex;

    /** 本次相对上次的变化量（index_value - previous_index），首次计算为 NULL */
    @Column(name = "change_amount", precision = 4, scale = 1)
    private BigDecimal changeAmount;

    /** 是否触发了单次下调幅度限制 */
    @Column(name = "drop_limited", nullable = false)
    private Boolean dropLimited;

    /** 窗口内是否存在因缺球场难度参数被排除的轮次 */
    @Column(name = "missing_params", nullable = false)
    private Boolean missingParams;

    @Column(name = "remark", length = 255)
    private String remark;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public String getPlayerName() {
        return player == null ? null : player.getName();
    }
}
