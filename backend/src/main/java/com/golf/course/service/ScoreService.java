package com.golf.course.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.golf.course.common.BizException;
import com.golf.course.common.Dict;
import com.golf.course.entity.Hole;
import com.golf.course.entity.Player;
import com.golf.course.entity.Score;
import com.golf.course.entity.Tournament;
import com.golf.course.repository.HoleRepository;
import com.golf.course.repository.PlayerRepository;
import com.golf.course.repository.ScoreRepository;
import com.golf.course.repository.TournamentRepository;

/**
 * 成绩业务：按洞录入杆数并与标准杆对比。
 *
 * <p>核心规则：① 球员 / 赛事 / 球洞必须存在；② 单洞杆数须 ≥ 1；③ 录入后可通过
 * {@code toPar}（杆数 - par）查看相对标准杆的成绩。</p>
 */
@Service
public class ScoreService {

    private final ScoreRepository scoreRepository;
    private final PlayerRepository playerRepository;
    private final TournamentRepository tournamentRepository;
    private final HoleRepository holeRepository;

    public ScoreService(ScoreRepository scoreRepository, PlayerRepository playerRepository,
                        TournamentRepository tournamentRepository, HoleRepository holeRepository) {
        this.scoreRepository = scoreRepository;
        this.playerRepository = playerRepository;
        this.tournamentRepository = tournamentRepository;
        this.holeRepository = holeRepository;
    }

    public List<Score> list(Long tournamentId, Long playerId) {
        if (tournamentId != null && playerId != null) {
            return scoreRepository.findByTournamentIdAndPlayerId(tournamentId, playerId);
        }
        if (tournamentId != null) {
            return scoreRepository.findByTournamentId(tournamentId);
        }
        return scoreRepository.findAll();
    }

    /**
     * 录入一条按洞成绩。收口边界：赛事一旦「已结束」并冻结正式排行榜快照，
     * 任何补录 / 修改 / 删除都被拒绝——正式名次只能来自收口时冻结的那份快照。
     * 同时与赛事收口共用赛事行悲观写锁，避免「收口校验通过的瞬间有人并发录杆」
     * 把未确认成绩带进正式快照。
     */
    @Transactional
    public Score record(Score input) {
        if (input.getTournament() == null || input.getTournament().getId() == null) {
            throw new BizException("必须指定赛事");
        }
        Long tournamentId = input.getTournament().getId();
        Tournament locked = tournamentRepository.findWithLockById(tournamentId)
                .orElseThrow(() -> new BizException("赛事不存在：" + tournamentId));
        if ("已结束".equals(locked.getStatus())) {
            throw new BizException("赛事「" + locked.getName()
                    + "」已结束，正式排行榜快照已冻结，不能再补录或修改成绩");
        }
        Player player = resolvePlayer(input.getPlayer());
        Hole hole = resolveHole(input.getHole());
        if (input.getRoundNo() == null || input.getRoundNo() < 1) {
            throw new BizException("轮次必须大于 0");
        }
        if (input.getStrokes() == null || input.getStrokes() < Dict.MIN_STROKES) {
            throw new BizException("单洞杆数须 ≥ " + Dict.MIN_STROKES);
        }
        Score score = new Score();
        score.setPlayer(player);
        score.setTournament(locked);
        score.setHole(hole);
        score.setRoundNo(input.getRoundNo());
        score.setStrokes(input.getStrokes());
        score.setCreatedAt(LocalDateTime.now());
        return scoreRepository.save(score);
    }

    private Player resolvePlayer(Player p) {
        if (p == null || p.getId() == null) {
            throw new BizException("必须指定球员");
        }
        return playerRepository.findById(p.getId())
                .orElseThrow(() -> new BizException("球员不存在：" + p.getId()));
    }

    private Hole resolveHole(Hole h) {
        if (h == null || h.getId() == null) {
            throw new BizException("必须指定球洞");
        }
        return holeRepository.findById(h.getId())
                .orElseThrow(() -> new BizException("球洞不存在：" + h.getId()));
    }
}
