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
import com.golf.course.entity.TournamentRoundHole;
import com.golf.course.repository.HoleRepository;
import com.golf.course.repository.PlayerRepository;
import com.golf.course.repository.ScoreRepository;
import com.golf.course.repository.TournamentRepository;
import com.golf.course.repository.TournamentRoundHoleRepository;

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
    private final TournamentRoundHoleRepository roundHoleRepository;

    public ScoreService(ScoreRepository scoreRepository, PlayerRepository playerRepository,
                        TournamentRepository tournamentRepository, HoleRepository holeRepository,
                        TournamentRoundHoleRepository roundHoleRepository) {
        this.scoreRepository = scoreRepository;
        this.playerRepository = playerRepository;
        this.tournamentRepository = tournamentRepository;
        this.holeRepository = holeRepository;
        this.roundHoleRepository = roundHoleRepository;
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
        validateHoleForTournament(locked, hole);
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

    /**
     * 校验录入的洞属于该赛事本轮应打洞：
     * 已开赛 → 必须在开赛冻结清单内（洞后来改维护 / 封闭不影响本轮，仍可录杆）；
     * 报名中 → 必须属于赛事绑定球场且当前开放（与开赛校验口径一致）。
     */
    private void validateHoleForTournament(Tournament tournament, Hole hole) {
        List<TournamentRoundHole> frozen =
                roundHoleRepository.findByTournamentIdOrderByHoleNoAsc(tournament.getId());
        if (!frozen.isEmpty()) {
            boolean inFrozen = frozen.stream().anyMatch(rh -> hole.getHoleNo().equals(rh.getHoleNo()));
            if (!inFrozen) {
                throw new BizException("第 " + hole.getHoleNo() + " 洞不在赛事「" + tournament.getName()
                        + "」开赛时冻结的本轮应打球洞清单内，不能录入本轮成绩");
            }
            return;
        }
        if (tournament.getCourse() == null) {
            throw new BizException("赛事「" + tournament.getName()
                    + "」尚未绑定球场，不能界定应打球洞，请先在赛事编辑中绑定球场");
        }
        if (hole.getCourse() == null || !tournament.getCourse().getId().equals(hole.getCourse().getId())) {
            throw new BizException("第 " + hole.getHoleNo() + " 洞不属于赛事「" + tournament.getName()
                    + "」绑定的「" + tournament.getCourse().getName() + "」，不能录入该赛事成绩");
        }
        if (!"开放".equals(hole.getStatus())) {
            throw new BizException("第 " + hole.getHoleNo() + " 洞当前为「" + hole.getStatus()
                    + "」，非应打球洞，不能录入；修复后可在开赛校验中放行");
        }
    }
}
