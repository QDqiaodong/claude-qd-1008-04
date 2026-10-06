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

import lombok.Getter;
import lombok.Setter;

/**
 * 记分卡逐洞明细：草稿工作区 + 争议/裁决的工作数据。
 *
 * <p>同一张卡内 {@code holeNo} 唯一（DB 唯一约束兜底重复洞号）。提交成功后这些行仍保留，
 * 但正式成绩只读 {@link Scorecard#getSnapshotJson()} 快照；裁判修正时会先把修正杆数
 * 写到 {@link #ruledStrokes}，重新固化快照时以其覆盖 {@link #strokes}。</p>
 */
@Entity
@Table(name = "scorecard_hole", uniqueConstraints = {
        @UniqueConstraint(name = "uk_card_hole", columnNames = {"scorecard_id", "hole_no"})
})
@Getter
@Setter
public class ScorecardHole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "scorecard_id", nullable = false)
    private Scorecard scorecard;

    @Column(name = "hole_no", nullable = false)
    private Integer holeNo;

    /** 录杆时该洞的标准杆（随工作数据留存） */
    @Column(name = "par", nullable = false)
    private Integer par;

    /** 当前工作杆数；草稿可空（表示未录） */
    @Column(name = "strokes")
    private Integer strokes;

    /** 球员对该洞的争议理由；非空即表示该洞在争议 */
    @Column(name = "dispute_reason", length = 255)
    private String disputeReason;

    /** 裁判裁决后的修正杆数；为空表示尚未裁决 */
    @Column(name = "ruled_strokes")
    private Integer ruledStrokes;

    /** 裁判对该洞的裁决说明 */
    @Column(name = "ruling_note", length = 255)
    private String rulingNote;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
