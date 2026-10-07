package com.golf.course.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.Setter;

/**
 * 球场：赛事绑定的开赛场地。一场赛事开赛时按 {@link #holeCount} 界定应打球洞集合，
 * 再逐一校验这些洞是否「开放且标准杆、码距合法」。
 *
 * <p>球场与球洞为 1 : N；洞号唯一约束为「同球场内洞号唯一」（uk_course_hole_no）。</p>
 *
 * <p>{@link #version} 为乐观锁：多人同时维护球场基础信息时只有基于最新版本的一次提交成功。</p>
 */
@Entity
@Table(name = "course")
@Getter
@Setter
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 球场编号，唯一 */
    @Column(name = "course_no", nullable = false, unique = true, length = 24)
    private String courseNo;

    @Column(name = "name", nullable = false, length = 48)
    private String name;

    /** 应配置洞数（1 ~ 18）：开赛校验按 1..holeCount 逐一读取球洞 */
    @Column(name = "hole_count", nullable = false)
    private Integer holeCount;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
