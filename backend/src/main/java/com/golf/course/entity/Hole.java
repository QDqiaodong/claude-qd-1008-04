package com.golf.course.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * 球洞：球场的基本组成单元。
 *
 * <p>每个洞有标准杆 par 与码距 yardage；状态开放可打、维护中表示封洞养护、封闭为长期停用。</p>
 */
@Entity
@Table(name = "hole")
@Getter
@Setter
public class Hole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 洞号，1 ~ 18，唯一 */
    @Column(name = "hole_no", nullable = false, unique = true)
    private Integer holeNo;

    /** 标准杆：3 / 4 / 5 */
    @Column(name = "par", nullable = false)
    private Integer par;

    /** 码距（码） */
    @Column(name = "yardage", nullable = false)
    private Integer yardage;

    /** 开放 / 维护 / 封闭 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
