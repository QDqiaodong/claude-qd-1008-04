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
 * 球洞：球场的基本组成单元。
 *
 * <p>每个洞有标准杆 par 与码距 yardage；状态开放可打、维护中表示封洞养护、封闭为长期停用。
 * 球洞归属于某个球场（{@link #course}），同球场内洞号唯一（uk_course_hole_no）。</p>
 *
 * <p>{@link #version} 为乐观锁：两个管理员同时维护同一球洞（改状态 / 改基础数据）时，
 * 后提交的一方收到 409「数据已变化」，必须重新读取最新洞版本后再操作。</p>
 */
@Entity
@Table(name = "hole", uniqueConstraints = {
        @UniqueConstraint(name = "uk_course_hole_no", columnNames = {"course_id", "hole_no"})
})
@Getter
@Setter
public class Hole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属球场 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    /** 洞号，1 ~ 18，同球场内唯一 */
    @Column(name = "hole_no", nullable = false)
    private Integer holeNo;

    /** 标准杆：3 / 4 / 5 */
    @Column(name = "par", nullable = false)
    private Integer par;

    /** 码距（码，1 ~ 800） */
    @Column(name = "yardage", nullable = false)
    private Integer yardage;

    /** 开放 / 维护 / 封闭 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 球洞乐观锁版本号：开赛校验、并发维护同一洞时按最新版本串行化，
     * 过期的维护 / 开赛操作统一转 409 并返回当前洞状态。
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
