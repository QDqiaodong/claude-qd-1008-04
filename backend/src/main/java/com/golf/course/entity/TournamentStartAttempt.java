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
 * 开赛尝试审计流水：每一次「开始赛事」的请求（成功 / 被阻止 / 并发冲突）都追加一行，
 * 永不修改、永不删除。
 *
 * <p>它是开赛失败恢复机制的留痕落点：校验失败只新增一行「已阻止」、赛事保持「报名中」，
 * 不产生任何进行中数据（不冻结球洞、不清报名名单与成绩草稿）；管理员修复球洞后重新尝试，
 * 服务端永远重新读取最新球洞版本，旧的失败记录不会被复用，新尝试另起一行记录。</p>
 */
@Entity
@Table(name = "tournament_start_attempt")
@Getter
@Setter
public class TournamentStartAttempt {

    public static final String RESULT_SUCCESS = "已开赛";
    public static final String RESULT_BLOCKED = "已阻止";
    public static final String RESULT_CONFLICT = "并发冲突";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tournament_id", nullable = false)
    private Tournament tournament;

    /** 已开赛 / 已阻止 / 并发冲突 */
    @Column(name = "result", nullable = false, length = 16)
    private String result;

    /** 页面发起时携带的赛事版本号（可能为 null） */
    @Column(name = "expected_tournament_version")
    private Long expectedTournamentVersion;

    /** 页面发起时携带的球洞版本指纹（可能为 null） */
    @Column(name = "expected_hole_version", length = 128)
    private String expectedHoleVersion;

    /** 当时赛事最新版本号 */
    @Column(name = "current_tournament_version")
    private Long currentTournamentVersion;

    /** 当时球洞最新版本指纹 */
    @Column(name = "current_hole_version", length = 128)
    private String currentHoleVersion;

    /** 被阻止时逐洞原因的 JSON（洞号 / 当前状态 / 修复提示）；成功时为 null */
    @Column(name = "blocked_json", length = 4000)
    private String blockedJson;

    @Column(name = "message", length = 512)
    private String message;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
