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
 * 赛事开赛前球场可用性校验记录：一场赛事至多一行
 * （{@code uk_start_check_tournament} 兜底并发）。
 *
 * <p>由「开始（报名中 → 进行中）」专用事务维护，两种口径：</p>
 * <ul>
 *   <li><b>可开赛（{@link #TYPE_PASSED}）</b>：校验通过、赛事已翻为「进行中」，
 *       {@link #eligibleHoleNos} 冻结本次应打球洞清单。之后有人把球洞改成维护 / 封闭，
 *       本轮仍按这份冻结洞集进行（排行榜 / 记分卡都回读它），不静默改变本轮状态；
 *       后续新赛事必须重新校验。</li>
 *   <li><b>被阻止（{@link #TYPE_BLOCKED}）</b>：最近一次开赛尝试校验失败。赛事事务整体回滚，
 *       仍保持「报名中」（不留下半个进行中状态，报名名单与成绩草稿原样保留）；
 *       {@link #blockedHolesJson} 记录受影响洞号 / 当前状态 / 修复提示。
 *       每次重试都在赛事行锁 + 球洞悲观锁内重新读取最新球洞版本后整行覆盖，绝不复用旧结果。</li>
 * </ul>
 *
 * <p>纯查询（GET 开赛校验）永远实时重算，不读写本表；本表只承担冻结、留痕与失败恢复。</p>
 */
@Entity
@Table(name = "tournament_start_check", uniqueConstraints = {
        @UniqueConstraint(name = "uk_start_check_tournament", columnNames = {"tournament_id"})
})
@Getter
@Setter
public class TournamentStartCheck {

    public static final String TYPE_PASSED = "可开赛";
    public static final String TYPE_BLOCKED = "被阻止";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tournament_id", nullable = false)
    private Tournament tournament;

    /** 可开赛 / 被阻止 */
    @Column(name = "check_type", nullable = false, length = 16)
    private String checkType;

    /** 校验时赛事绑定的球场（冗余留痕，便于追溯当时按哪个球场校验） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id")
    private Course course;

    /** 校验通过时冻结的应打球洞号，逗号分隔（如 "1,2,3"）；被阻止时为 null */
    @Column(name = "eligible_hole_nos", length = 255)
    private String eligibleHoleNos;

    /**
     * 被阻止时的逐洞明细 JSON（受影响洞号 / 当前状态 / 标准杆 / 码距 / 修复提示）；
     * 通过时为 null。生产库为 MEDIUMTEXT（ddl-auto=none），length 只给测试环境 H2 用。
     */
    @Column(name = "blocked_holes_json", length = 16_777_215)
    private String blockedHolesJson;

    /** 校验时该球场全部球洞（含洞数之外的额外洞）的版本指纹，用于识别「球洞数据已变化」 */
    @Column(name = "course_version_hash")
    private Long courseVersionHash;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
