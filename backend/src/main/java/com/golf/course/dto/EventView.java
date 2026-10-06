package com.golf.course.dto;

import java.time.LocalDateTime;

import com.golf.course.entity.ScorecardEvent;

/**
 * 记分卡流转事件视图：避免实体懒加载关联被直接序列化，
 * 只向前端暴露可追溯链路所需字段（动作、操作人、前后状态/版本/快照）。
 */
public record EventView(
        Long id,
        Long scorecardId,
        String action,
        String actor,
        String detail,
        String beforeStatus,
        String afterStatus,
        Long beforeVersion,
        Long afterVersion,
        String beforeJson,
        String afterJson,
        LocalDateTime createdAt) {

    public static EventView of(ScorecardEvent e) {
        return new EventView(
                e.getId(),
                e.getScorecard().getId(),
                e.getAction(),
                e.getActor(),
                e.getDetail(),
                e.getBeforeStatus(),
                e.getAfterStatus(),
                e.getBeforeVersion(),
                e.getAfterVersion(),
                e.getBeforeJson(),
                e.getAfterJson(),
                e.getCreatedAt());
    }
}
