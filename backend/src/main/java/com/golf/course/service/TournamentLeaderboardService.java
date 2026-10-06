package com.golf.course.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.golf.course.common.BizException;
import com.golf.course.entity.Tournament;
import com.golf.course.entity.TournamentLeaderboard;
import com.golf.course.repository.TournamentLeaderboardRepository;
import com.golf.course.repository.TournamentRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

/**
 * 赛事排行榜业务，演示 {@code OPTIMISTIC_FORCE_INCREMENT} 乐观锁。
 *
 * <p>排行榜是「共享实体」，多个并发写操作（如多名记分员同时刷新领先榜）会竞争同一行。
 * {@link #lockAndRead(Long)} 先用 {@code entityManager.lock(entity, LockModeType.OPTIMISTIC_FORCE_INCREMENT)}
 * 强制把版本号 +1 再读取——这意味着只要有并发者在我之后提交，我的写就会因版本冲突失败，
 * 从而把「预订/刷新竞态」暴露出来而非静默覆盖。这是本仓指定的后端并发演示技术点。</p>
 */
@Service
public class TournamentLeaderboardService {

    private final TournamentLeaderboardRepository leaderboardRepository;
    private final TournamentRepository tournamentRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public TournamentLeaderboardService(TournamentLeaderboardRepository leaderboardRepository,
                                        TournamentRepository tournamentRepository) {
        this.leaderboardRepository = leaderboardRepository;
        this.tournamentRepository = tournamentRepository;
    }

    public List<TournamentLeaderboard> list(Long tournamentId) {
        if (tournamentId != null) {
            return leaderboardRepository.findByTournamentId(tournamentId);
        }
        return leaderboardRepository.findAll();
    }

    public TournamentLeaderboard get(Long id) {
        return leaderboardRepository.findById(id)
                .orElseThrow(() -> new BizException("排行榜不存在：" + id));
    }

    @Transactional
    public TournamentLeaderboard create(TournamentLeaderboard input) {
        Tournament tournament = resolveTournament(input.getTournament());
        if (input.getRoundNo() == null || input.getRoundNo() < 1) {
            throw new BizException("轮次必须大于 0");
        }
        TournamentLeaderboard lb = new TournamentLeaderboard();
        lb.setTournament(tournament);
        lb.setRoundNo(input.getRoundNo());
        lb.setLeaderName(input.getLeaderName());
        lb.setTotalScore(input.getTotalScore());
        lb.setVersion(0L);
        lb.setUpdatedAt(LocalDateTime.now());
        return leaderboardRepository.save(lb);
    }

    /**
     * 锁定并读取：以 OPTIMISTIC_FORCE_INCREMENT 乐观锁方式读取排行榜。
     *
     * <p>每次调用都会把版本号 +1 后再返回实体；若期间有其它事务先提交，本事务提交时
     * 会因版本冲突抛 {@code OptimisticLockException}，从而拒绝这次读取/刷新。</p>
     */
    @Transactional
    public TournamentLeaderboard lockAndRead(Long id) {
        TournamentLeaderboard lb = get(id);
        entityManager.lock(lb, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        lb.setUpdatedAt(LocalDateTime.now());
        TournamentLeaderboard saved = leaderboardRepository.save(lb);
        // 读取：返回带最新版本号的实体，便于前端观察 version 自增
        return saved;
    }

    @Transactional
    public TournamentLeaderboard update(Long id, TournamentLeaderboard input) {
        TournamentLeaderboard lb = get(id);
        if (input.getLeaderName() != null) {
            lb.setLeaderName(input.getLeaderName());
        }
        if (input.getTotalScore() != null) {
            lb.setTotalScore(input.getTotalScore());
        }
        if (input.getRoundNo() != null && input.getRoundNo() >= 1) {
            lb.setRoundNo(input.getRoundNo());
        }
        lb.setUpdatedAt(LocalDateTime.now());
        return leaderboardRepository.save(lb);
    }

    private Tournament resolveTournament(Tournament tournament) {
        if (tournament == null || tournament.getId() == null) {
            throw new BizException("必须指定赛事");
        }
        return tournamentRepository.findById(tournament.getId())
                .orElseThrow(() -> new BizException("赛事不存在：" + tournament.getId()));
    }
}
