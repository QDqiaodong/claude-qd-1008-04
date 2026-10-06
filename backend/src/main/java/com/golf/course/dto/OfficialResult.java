package com.golf.course.dto;

/**
 * 正式排行榜行：只统计「已签核」记分卡的当前固化快照，
 * 草稿 / 待确认 / 争议中一律不计；历史快照不参与，杜绝重复计入。
 */
public record OfficialResult(
        Long playerId,
        String playerName,
        Integer roundNo,
        Long scorecardId,
        Long version,
        Integer totalStrokes,
        Integer totalPar,
        Integer toPar) {
}
