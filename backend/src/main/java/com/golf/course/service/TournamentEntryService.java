package com.golf.course.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.golf.course.common.BizException;
import com.golf.course.dto.EntryRequest;
import com.golf.course.entity.Player;
import com.golf.course.entity.Tournament;
import com.golf.course.entity.TournamentEntry;
import com.golf.course.repository.PlayerRepository;
import com.golf.course.repository.TournamentEntryRepository;
import com.golf.course.repository.TournamentRepository;

/**
 * 赛事参赛名单业务：登记 / 查询参赛球员与应打轮次，作为记分卡签核范围与收口检查口径。
 */
@Service
public class TournamentEntryService {

    private final TournamentEntryRepository entryRepository;
    private final TournamentRepository tournamentRepository;
    private final PlayerRepository playerRepository;

    public TournamentEntryService(TournamentEntryRepository entryRepository,
                                  TournamentRepository tournamentRepository,
                                  PlayerRepository playerRepository) {
        this.entryRepository = entryRepository;
        this.tournamentRepository = tournamentRepository;
        this.playerRepository = playerRepository;
    }

    @Transactional(readOnly = true)
    public List<TournamentEntry> list(Long tournamentId) {
        if (tournamentId == null) {
            return entryRepository.findAll();
        }
        return entryRepository.findByTournamentIdOrderByIdAsc(tournamentId);
    }

    @Transactional
    public TournamentEntry add(Long tournamentId, EntryRequest req) {
        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BizException("赛事不存在：" + tournamentId));
        Player player = playerRepository.findById(req.playerId())
                .orElseThrow(() -> new BizException("球员不存在：" + req.playerId()));
        if (entryRepository.existsByTournamentIdAndPlayerId(tournamentId, player.getId())) {
            throw new BizException("球员「" + player.getName() + "」已在该赛事参赛名单中");
        }
        TournamentEntry entry = new TournamentEntry();
        entry.setTournament(tournament);
        entry.setPlayer(player);
        entry.setRoundCount(req.roundCount());
        entry.setCreatedAt(LocalDateTime.now());
        return entryRepository.save(entry);
    }
}
