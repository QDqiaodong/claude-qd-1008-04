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
 * 全量差点重算批次：一次「按赛事日期分批」的全量重算的执行记录。
 *
 * <p>批次按赛事结束日期升序切片扫描已签核记分卡，汇总受影响球员后逐球员重算；
 * 单个球员失败不拖垮整批，失败明细落 {@code handicap_run_error}，
 * 批次最终以 已完成 / 部分失败 收口，成功 / 样本不足 / 失败计数齐全。</p>
 */
@Entity
@Table(name = "handicap_run")
@Getter
@Setter
public class HandicapRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 批次号，如 HR-20261006153000-123 */
    @Column(name = "run_no", nullable = false, unique = true, length = 24)
    private String runNo;

    /** 计算基准日：只取该日（含）之前已结束的轮次，并据此选取生效规则 */
    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "rule_id", nullable = false)
    private HandicapRule rule;

    /** 赛事日期分批范围（可选）：只扫描该范围内结束的赛事以圈定受影响球员 */
    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

    /** 每批赛事场数 */
    @Column(name = "batch_size", nullable = false)
    private Integer batchSize;

    /** 进行中 / 已完成 / 部分失败 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "total_players", nullable = false)
    private Integer totalPlayers;

    @Column(name = "success_count", nullable = false)
    private Integer successCount;

    @Column(name = "insufficient_count", nullable = false)
    private Integer insufficientCount;

    @Column(name = "fail_count", nullable = false)
    private Integer failCount;

    @Column(name = "triggered_by", length = 48)
    private String triggeredBy;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public String getRuleName() {
        return rule == null ? null : rule.getRuleName();
    }
}
