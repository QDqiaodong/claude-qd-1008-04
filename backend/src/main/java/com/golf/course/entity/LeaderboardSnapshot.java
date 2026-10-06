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
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.Setter;

/**
 * 赛事排行榜快照：一场赛事至多一行（{@code uk_snapshot_tournament}）。
 *
 * <p>三种口径：</p>
 * <ul>
 *   <li><b>正式快照（{@code 正式快照}）</b>：赛事收口到「已结束」的同一事务内，
 *       基于收口前确认的全部参赛成绩冻结生成。冻结体存在 {@link #officialPayloadJson}，
 *       之后刷新页面 / 接口永远只返回这份 JSON；成绩后来被补录 / 修改 / 删除都不再改变名次。</li>
 *   <li><b>生成失败（{@code 生成失败}）</b>：收口校验未过（缺成绩 / 成绩属于不存在或
 *       非参赛球员 / 洞号杆数不合法）时留下的失败留痕。赛事状态在独立事务里回滚为「进行中」，
 *       不产生任何正式结果行（不留下半份正式结果），逐条原因存 {@link #failureReasons}；
 *       临时排名仍可正常查询。</li>
 *   <li><b>临时结果</b>：报名中 / 进行中的排行榜不落库，按当前已录入成绩实时计算并明确标注；
 *       本表不保留「临时结果」行。</li>
 * </ul>
 *
 * <p>{@link #version} 为乐观锁：两个并发收口事务竞争同一行时，只有一份正式快照能提交，
 * 另一份收到 409「数据已变化」。</p>
 */
@Entity
@Table(name = "leaderboard_snapshot")
@Getter
@Setter
public class LeaderboardSnapshot {

    public static final String TYPE_OFFICIAL = "正式快照";
    public static final String TYPE_FAILED = "生成失败";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tournament_id", nullable = false)
    private Tournament tournament;

    /** 正式快照 / 生成失败 */
    @Column(name = "snapshot_type", nullable = false, length = 16)
    private String snapshotType;

    /** 生成时的赛事状态（已结束 / 进行中），便于历史兼容查询 */
    @Column(name = "status_snapshot", length = 16)
    private String statusSnapshot;

    /** 收口失败逐条原因（含赛事、球员、洞号）；正式快照时为 null */
    @Column(name = "failure_reasons", length = 4000)
    private String failureReasons;

    /**
     * 正式快照冻结体 JSON（完整名次 + 每轮逐洞成绩），生成后永不修改。
     * 生产库由 schema.sql 建为 MEDIUMTEXT（ddl-auto=none）；length 只给测试环境 H2 自动建表用。
     */
    @Column(name = "official_payload_json", length = 16_777_215)
    private String officialPayloadJson;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
