package com.golf.course.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import lombok.Getter;
import lombok.Setter;

/**
 * 差点计算规则：带生效日期的版本化规则。
 *
 * <p>同一生效日期只允许一条规则；计算时按「基准日 ≤ 生效日期」取最新一条生效规则，
 * 因此新规则只影响生效日之后（含）的计算结果，历史计算按当时规则留痕可追溯。</p>
 *
 * <p>规则一旦被任何计算或批次引用即不可删除，只能新增一条更新生效日期的规则。</p>
 *
 * <p>bestCountTable 为「样本轮数:采用最低差值个数」的映射（如 3:1,4:1,...,20:8），
 * 样本数落在两档之间时向下取最近档；maxDifferential 为单轮差值截断上限，
 * maxDrop 为单次重算允许的最大下调幅度。</p>
 */
@Entity
@Table(name = "handicap_rule")
@Getter
@Setter
public class HandicapRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规则名称，如「WHS标准口径v1」 */
    @Column(name = "rule_name", nullable = false, length = 48)
    private String ruleName;

    /** 生效日期：基准日 ≥ 该日期的计算才使用本规则 */
    @Column(name = "effective_date", nullable = false, unique = true)
    private LocalDate effectiveDate;

    /** 最近 N 轮窗口（WHS 为 20） */
    @Column(name = "rounds_window", nullable = false)
    private Integer roundsWindow;

    /** 最少样本轮次：可计轮次少于此值时状态为「样本不足」，不出指数 */
    @Column(name = "min_rounds", nullable = false)
    private Integer minRounds;

    /** 「样本轮数:采用最低差值个数」映射，如 3:1,4:1,5:1,6:2,...,20:8 */
    @Column(name = "best_count_table", nullable = false, length = 255)
    private String bestCountTable;

    /** 单轮差值截断上限：超过按上限计入，避免录入错误拉高指数 */
    @Column(name = "max_differential", nullable = false, precision = 4, scale = 1)
    private BigDecimal maxDifferential;

    /** 单次重算允许的最大下调幅度 */
    @Column(name = "max_drop", nullable = false, precision = 4, scale = 1)
    private BigDecimal maxDrop;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 派生（非持久化）：是否已被计算 / 批次引用——被引用的规则不可删除 */
    @Transient
    private Boolean referenced;
}
