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
 * 全量重算失败明细：批次扫描失败或单个球员重算失败时追加一行，
 * 失败不中断批次，全部留痕供事后排查与定向补算。
 */
@Entity
@Table(name = "handicap_run_error")
@Getter
@Setter
public class HandicapRunError {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "run_id", nullable = false)
    private HandicapRun run;

    /** 出错的赛事日期分批标签，如「2026-06-14 ~ 2026-07-12 · 3场」 */
    @Column(name = "batch_label", length = 64)
    private String batchLabel;

    /** 出错球员（批次级失败为 NULL） */
    @Column(name = "player_id")
    private Long playerId;

    @Column(name = "player_name", length = 48)
    private String playerName;

    @Column(name = "message", length = 255)
    private String message;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
