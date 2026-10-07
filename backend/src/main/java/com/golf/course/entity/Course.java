package com.golf.course.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.Setter;

/**
 * 球场：赛事绑定的比赛场地，球洞按球场隔离。
 *
 * <p>{@link #holeCount} 为该球场本场赛事应打的连续洞号数（应打洞号 = 1 ~ holeCount），
 * 合法区间 1 ~ 18。开赛前校验按此读取「全部应打球洞」：缺号即「未配置」、
 * 维护 / 封闭即不可用、par / yardage 不合法即基础数据不完整，任一存在都阻止开赛。</p>
 */
@Entity
@Table(name = "course", uniqueConstraints = {
        @UniqueConstraint(name = "uk_course_no", columnNames = {"course_no"})
})
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

    /** 应打球洞数（1 ~ 18）：应打洞号为连续的 1 ~ holeCount */
    @Column(name = "hole_count", nullable = false)
    private Integer holeCount;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
