package com.golf.course.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.golf.course.entity.Scorecard;
import com.golf.course.entity.ScorecardHole;

/**
 * 记分卡详情视图：主单 + 逐洞工作数据，一次返回给前端三个角色页面。
 * holes 按洞号升序；前端以 version 做并发操作的前提版本号。
 */
public record ScorecardView(
        Long id,
        Long tournamentId,
        String tournamentName,
        String tournamentStatus,
        Long playerId,
        String playerName,
        Integer roundNo,
        String status,
        Long version,
        Integer expectedHoles,
        Integer totalStrokes,
        String snapshotJson,
        LocalDateTime submittedAt,
        LocalDateTime confirmedAt,
        String lastSubmitter,
        LocalDateTime updatedAt,
        List<HoleRow> holes) {

    public record HoleRow(
            Long id,
            Integer holeNo,
            Integer par,
            Integer strokes,
            String disputeReason,
            Integer ruledStrokes,
            String rulingNote) {

        public static HoleRow of(ScorecardHole h) {
            return new HoleRow(h.getId(), h.getHoleNo(), h.getPar(), h.getStrokes(),
                    h.getDisputeReason(), h.getRuledStrokes(), h.getRulingNote());
        }
    }

    public static ScorecardView of(Scorecard c, List<ScorecardHole> holes) {
        return new ScorecardView(
                c.getId(),
                c.getTournament().getId(),
                c.getTournament().getName(),
                c.getTournament().getStatus(),
                c.getPlayer().getId(),
                c.getPlayer().getName(),
                c.getRoundNo(),
                c.getStatus(),
                c.getVersion(),
                c.getExpectedHoles(),
                c.getTotalStrokes(),
                c.getSnapshotJson(),
                c.getSubmittedAt(),
                c.getConfirmedAt(),
                c.getLastSubmitter(),
                c.getUpdatedAt(),
                holes.stream().map(HoleRow::of).toList());
    }
}
