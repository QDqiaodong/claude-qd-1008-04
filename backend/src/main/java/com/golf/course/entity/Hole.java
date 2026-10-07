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
 * 球洞：某个球场的基本组成单元。
 *
 * <p>每个洞有标准杆 par 与码距 yardage；状态开放可打、维护中表示封洞养护、封闭为长期停用。
 * 球洞按球场隔离：(球场, 洞号) 唯一，同一洞号可以分属不同球场。</p>
 *
 * <p>{@link #version} 为 JPA {@link Version} 乐观锁：管理员并发维护同一球洞时，
 * 只有基于最新版本的一次写操作能提交，其余收到 409「数据已变化」。
 * 开赛校验在赛事行悲观锁内读取最新球洞版本（并发开赛由赛事行锁串行化），
 * 与球洞乐观锁共同保证开赛与并发维护不会互相使用旧版本。</p>
 */
@Entity
@Table(name = "hole", uniqueConstraints = {
        @UniqueConstraint(name = "uk_hole_course_no", columnNames = {"course_id", "hole_no"})
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

    /** 洞号，1 ~ 18；同一球场内唯一 */
    @Column(name = "hole_no", nullable = false)
    private Integer holeNo;

    /** 标准杆：3 / 4 / 5 */
    @Column(name = "par", nullable = false)
    private Integer par;

    /** 码距（码，须 > 0） */
    @Column(name = "yardage", nullable = false)
    private Integer yardage;

    /** 开放 / 维护 / 封闭 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 乐观锁版本号：并发维护同一球洞时过期一方收到 409 */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
