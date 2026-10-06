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
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.Setter;

/**
 * 赛事记分卡：记分员 / 球员 / 裁判围绕同「赛事 × 轮次 × 球员」一轮逐洞成绩
 * 完成 提交 → 确认 / 争议 → 裁决 → 再确认 → 签核 闭环的主单。
 *
 * <p>状态机：{@link com.golf.course.common.Dict#CARD_DRAFT 草稿}
 * → {@link com.golf.course.common.Dict#CARD_PENDING 待球员确认}
 * ⇄ {@link com.golf.course.common.Dict#CARD_DISPUTED 争议中}
 * → {@link com.golf.course.common.Dict#CARD_SIGNED 已签核}（终态，不可撤回）。</p>
 *
 * <p>{@code version} 为 JPA {@link Version} 乐观锁：多人并发提交、确认或裁决同一张卡时，
 * 只有基于最新版本的一次写操作能提交，其余抛 {@code OptimisticLockException}
 * （全局转 409，前端据此提示「数据已变化」并刷新当前状态），不会静默覆盖。</p>
 *
 * <p>{@code snapshotJson} 是本次固化的逐洞快照：提交与每次裁决时重写；
 * 普通草稿编辑改不到它，历史版本另存 {@code scorecard_event}，正式排行榜只认已签核卡的当前快照，
 * 因此历史版本不会重复计入正式成绩。</p>
 */
@Entity
@Table(name = "scorecard", uniqueConstraints = {
        @UniqueConstraint(name = "uk_scorecard_tour_player_round",
                columnNames = {"tournament_id", "player_id", "round_no"})
})
@Getter
@Setter
public class Scorecard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tournament_id", nullable = false)
    private Tournament tournament;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    /** 草稿 / 待球员确认 / 争议中 / 已签核 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 乐观锁版本号：每次状态/快照写操作 +1 */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** 应打球洞数（提交时按当前开放洞数固化） */
    @Column(name = "expected_holes", nullable = false)
    private Integer expectedHoles;

    /** 最近一次固化快照的总杆数 */
    @Column(name = "total_strokes")
    private Integer totalStrokes;

    /** 本次已固化快照（提交 / 裁决后重写），历史快照见 scorecard_event */
    @Column(name = "snapshot_json")
    private String snapshotJson;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** 最近一次提交/裁决的记分员 */
    @Column(name = "last_submitter", length = 48)
    private String lastSubmitter;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public String getPlayerName() {
        return player == null ? null : player.getName();
    }

    public String getTournamentName() {
        return tournament == null ? null : tournament.getName();
    }
}
