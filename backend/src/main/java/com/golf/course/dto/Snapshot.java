package com.golf.course.dto;

import java.util.List;

/**
 * 记分卡固化快照模型：提交 / 每次裁决时序列化为 JSON 存入
 * {@code scorecard.snapshot_json} 与 {@code scorecard_event.before_json/after_json}。
 *
 * <p>快照只承载事实数据（洞号、标准杆、当时杆数、争议与裁决痕迹），
 * 是「正式成绩」的唯一口径；历史快照永不修改。</p>
 */
public record Snapshot(
        Integer roundNo,
        Integer totalStrokes,
        List<Hole> holes) {

    public record Hole(
            Integer holeNo,
            Integer par,
            Integer strokes,
            String disputeReason,
            Integer ruledStrokes,
            String rulingNote) {
    }
}
