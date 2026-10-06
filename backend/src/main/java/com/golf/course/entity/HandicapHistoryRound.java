package com.golf.course.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

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
 * 单次差点计算的轮次明细：进入「最近 N 轮」窗口的每个已完成轮次一行。
 *
 * <p>记录该轮的总杆、球场难度参数、算出的差值、是否被采用（最低 K 个），
 * 未采用时给出排除原因（缺球场难度参数 / 差值未进入最低 K 个 / 快照无法解析），
 * 差值超上限被截断时 capped=true —— 每次计算的采用与排除全部可追溯。</p>
 */
@Entity
@Table(name = "handicap_history_round")
@Getter
@Setter
public class HandicapHistoryRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "history_id", nullable = false)
    private HandicapHistory history;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tournament_id", nullable = false)
    private Tournament tournament;

    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    /** 比赛日期（取赛事结束日，无结束日则取开始日） */
    @Column(name = "play_date")
    private LocalDate playDate;

    /** 该轮总杆（来自已签核记分卡快照） */
    @Column(name = "gross_score")
    private Integer grossScore;

    /** 计算时赛事上维护的球场难度值；旧数据缺参时为 NULL */
    @Column(name = "course_rating", precision = 4, scale = 1)
    private BigDecimal courseRating;

    /** 计算时赛事上维护的坡度难度；旧数据缺参时为 NULL */
    @Column(name = "slope_rating")
    private Integer slopeRating;

    /** 轮差值 = (113 / slope) × (总杆 − courseRating)，保留一位小数；不可计时为 NULL */
    @Column(name = "differential", precision = 5, scale = 1)
    private BigDecimal differential;

    /** 差值是否因超规则上限被截断（截断后仍参与采用） */
    @Column(name = "capped", nullable = false)
    private Boolean capped;

    /** 是否被采用（进入最低 K 个差值） */
    @Column(name = "is_used", nullable = false)
    private Boolean used;

    /** 未采用 / 不可计的排除原因；被采用的轮次为 NULL */
    @Column(name = "exclusion_reason", length = 255)
    private String exclusionReason;

    public String getTournamentName() {
        return tournament == null ? null : tournament.getName();
    }
}
