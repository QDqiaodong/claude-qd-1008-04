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
 * 记分卡流转事件：每次提交、确认、争议、维持、修正都追加一行，永不修改/删除。
 *
 * <p>{@code beforeJson} / {@code afterJson} 分别保存操作前后的完整逐洞快照，
 * 配合 {@code beforeVersion} / {@code afterVersion} 构成可追溯链路：
 * 既能追查每次提交与裁决前后的成绩，也能确保只有当前已签核快照计入正式排行榜，
 * 历史版本永不重复计入。</p>
 */
@Entity
@Table(name = "scorecard_event")
@Getter
@Setter
public class ScorecardEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scorecard_id", nullable = false)
    private Scorecard scorecard;

    /** 提交 / 确认 / 争议 / 维持 / 修正 */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "actor", nullable = false, length = 48)
    private String actor;

    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "before_status", length = 16)
    private String beforeStatus;

    @Column(name = "after_status", length = 16)
    private String afterStatus;

    @Column(name = "before_version")
    private Long beforeVersion;

    @Column(name = "after_version")
    private Long afterVersion;

    @Column(name = "before_json")
    private String beforeJson;

    @Column(name = "after_json")
    private String afterJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
