package com.golf.course.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 缺球场难度参数的赛事及其受影响球员（有已签核轮次但因缺参被排除）。
 * 补齐参数后可对受影响球员做定向重算。
 */
public record MissingParamsView(
        Long tournamentId,
        String tournamentNo,
        String tournamentName,
        String status,
        LocalDate endDate,
        BigDecimal courseRating,
        Integer slopeRating,
        List<AffectedPlayer> affectedPlayers) {

    public record AffectedPlayer(Long playerId, String playerNo, String playerName) {
    }
}
