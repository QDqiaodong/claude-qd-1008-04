package com.golf.course.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.golf.course.common.BizException;
import com.golf.course.common.ConflictException;
import com.golf.course.common.Dict;
import com.golf.course.dto.ConfirmCardRequest;
import com.golf.course.dto.DisputeRequest;
import com.golf.course.dto.OfficialResult;
import com.golf.course.dto.RulingRequest;
import com.golf.course.dto.SaveHoleRequest;
import com.golf.course.dto.SignoffCheck;
import com.golf.course.dto.Snapshot;
import com.golf.course.dto.SubmitCardRequest;
import com.golf.course.entity.Hole;
import com.golf.course.entity.Player;
import com.golf.course.entity.Scorecard;
import com.golf.course.entity.ScorecardEvent;
import com.golf.course.entity.ScorecardHole;
import com.golf.course.entity.Tournament;
import com.golf.course.entity.TournamentEntry;
import com.golf.course.repository.HoleRepository;
import com.golf.course.repository.PlayerRepository;
import com.golf.course.repository.ScorecardEventRepository;
import com.golf.course.repository.ScorecardHoleRepository;
import com.golf.course.repository.ScorecardRepository;
import com.golf.course.repository.TournamentEntryRepository;
import com.golf.course.repository.TournamentRepository;

/**
 * 赛事记分卡签核闭环核心业务。
 *
 * <p>角色分工：记分员维护草稿并提交；球员只处理自己的待确认卡（整卡确认 / 按洞争议）；
 * 裁判只处理争议中卡（维持 / 修正后发回球员再确认）。</p>
 *
 * <p>四条不可绕过的规则：
 * <ol>
 *   <li>提交校验——赛事必须「进行中」、应打（当前开放）球洞均有 ≥1 的合法杆数、洞号不重复；
 *       提交时固化快照，之后普通草稿编辑无法改写快照。</li>
 *   <li>确认终态——已签核不可撤回；争议中 / 待确认 / 草稿卡均不计入正式排行榜。</li>
 *   <li>全程留痕——每次提交、争议、确认、裁决都向 {@code scorecard_event} 追加前后快照与版本；
 *       正式排行榜只解析已签核卡的当前快照，历史版本不重复计入。</li>
 *   <li>乐观并发——所有写操作都必须携带页面加载时的版本号；过期操作抛
 *       {@link ConflictException}（409），消息带当前状态，由前端提示并刷新，绝不静默覆盖。</li>
 * </ol>
 */
@Service
public class ScorecardService {

    private final ScorecardRepository cardRepository;
    private final ScorecardHoleRepository holeLineRepository;
    private final ScorecardEventRepository eventRepository;
    private final TournamentRepository tournamentRepository;
    private final PlayerRepository playerRepository;
    private final HoleRepository holeRepository;
    private final TournamentEntryRepository entryRepository;
    private final ObjectMapper objectMapper;

    public ScorecardService(ScorecardRepository cardRepository,
                            ScorecardHoleRepository holeLineRepository,
                            ScorecardEventRepository eventRepository,
                            TournamentRepository tournamentRepository,
                            PlayerRepository playerRepository,
                            HoleRepository holeRepository,
                            TournamentEntryRepository entryRepository,
                            ObjectMapper objectMapper) {
        this.cardRepository = cardRepository;
        this.holeLineRepository = holeLineRepository;
        this.eventRepository = eventRepository;
        this.tournamentRepository = tournamentRepository;
        this.playerRepository = playerRepository;
        this.holeRepository = holeRepository;
        this.entryRepository = entryRepository;
        this.objectMapper = objectMapper;
    }

    // ================================================================
    // 查询
    // ================================================================

    /** 记分员视角：按赛事列出全部记分卡（含各状态）。 */
    @Transactional(readOnly = true)
    public List<Scorecard> listByTournament(Long tournamentId) {
        if (tournamentId == null) {
            return cardRepository.findAll();
        }
        return cardRepository.findByTournamentIdOrderByRoundNoAscIdAsc(tournamentId);
    }

    /** 球员视角：只返回自己的卡；不提供按他人查询的入口。 */
    @Transactional(readOnly = true)
    public List<Scorecard> listMine(Long playerId) {
        Player player = requirePlayer(playerId);
        return cardRepository.findByPlayerIdOrderByUpdatedAtDesc(player.getId());
    }

    /** 裁判视角：只返回争议中的卡。 */
    @Transactional(readOnly = true)
    public List<Scorecard> listDisputed(Long tournamentId) {
        return listByTournament(tournamentId).stream()
                .filter(c -> Dict.CARD_DISPUTED.equals(c.getStatus()))
                .toList();
    }

    @Transactional(readOnly = true)
    public com.golf.course.dto.ScorecardView getView(Long id) {
        Scorecard card = getCard(id);
        return com.golf.course.dto.ScorecardView.of(card, lines(id));
    }

    @Transactional(readOnly = true)
    public List<com.golf.course.dto.EventView> events(Long id) {
        getCard(id);
        return eventRepository.findByScorecardIdOrderByIdAsc(id).stream()
                .map(com.golf.course.dto.EventView::of)
                .toList();
    }

    // ================================================================
    // 记分员：取卡 / 建草稿 / 保存逐洞草稿
    // ================================================================

    /**
     * 记分员按 赛事 × 轮次 × 球员 取草稿：不存在则在参赛名单内自动建一张草稿。
     * 只有「报名中 / 进行中」的赛事允许建草稿；已结束赛事的成绩不得再动。
     */
    @Transactional
    public Scorecard getOrCreateDraft(Long tournamentId, Long playerId, Integer roundNo) {
        if (roundNo == null || roundNo < 1) {
            throw new BizException("轮次必须大于 0");
        }
        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BizException("赛事不存在：" + tournamentId));
        Player player = requirePlayer(playerId);
        TournamentEntry entry = entryRepository.findByTournamentIdAndPlayerId(tournamentId, playerId)
                .orElseThrow(() -> new BizException("球员「" + player.getName() + "」不在该赛事参赛名单中，无法记分"));
        if (roundNo > entry.getRoundCount()) {
            throw new BizException("该球员本场赛事应打 " + entry.getRoundCount() + " 轮，不存在第 " + roundNo + " 轮");
        }
        if ("已结束".equals(tournament.getStatus())) {
            throw new BizException("赛事已结束，不能再维护记分卡草稿");
        }
        return cardRepository.findByTournamentIdAndPlayerIdAndRoundNo(tournamentId, playerId, roundNo)
                .orElseGet(() -> {
                    Scorecard card = new Scorecard();
                    card.setTournament(tournament);
                    card.setPlayer(player);
                    card.setRoundNo(roundNo);
                    card.setStatus(Dict.CARD_DRAFT);
                    card.setExpectedHoles(openHoles().size());
                    card.setCreatedAt(LocalDateTime.now());
                    card.setUpdatedAt(LocalDateTime.now());
                    return cardRepository.save(card);
                });
    }

    /**
     * 记分员保存单洞草稿杆数。只允许在「草稿」状态进行——固化后的快照
     * 不能通过普通编辑改写；并发两人同时录杆时，过期版本返回 409。
     */
    @Transactional
    public com.golf.course.dto.ScorecardView saveHole(Long id, SaveHoleRequest req) {
        Scorecard card = getCard(id);
        checkVersion(card, req.expectedVersion());
        if (!Dict.CARD_DRAFT.equals(card.getStatus())) {
            throw new BizException("记分卡已提交（当前：" + card.getStatus()
                    + "），快照已固化，普通编辑不能改写；如需改判请走争议/裁决流程");
        }
        Hole hole = holeRepository.findByHoleNo(req.holeNo())
                .orElseThrow(() -> new BizException("球洞不存在：第 " + req.holeNo() + " 洞"));
        if (!"开放".equals(hole.getStatus())) {
            throw new BizException("第 " + hole.getHoleNo() + " 洞当前为「" + hole.getStatus()
                    + "」，非应打球洞，不能录入");
        }
        ScorecardHole line = holeLineRepository.findByScorecardIdAndHoleNo(id, req.holeNo())
                .orElseGet(() -> {
                    ScorecardHole nl = new ScorecardHole();
                    nl.setScorecard(card);
                    nl.setHoleNo(hole.getHoleNo());
                    nl.setPar(hole.getPar());
                    return nl;
                });
        line.setPar(hole.getPar());
        line.setStrokes(req.strokes());
        line.setUpdatedAt(LocalDateTime.now());
        holeLineRepository.save(line);
        // 触摸主单的 updatedAt 让 @Version 自增：两人并发录同一卡时，
        // 即使各自录的是不同洞，后提交者也会因版本过期收到 409，而不是互相静默覆盖。
        card.setUpdatedAt(LocalDateTime.now());
        cardRepository.saveAndFlush(card);
        return getView(id);
    }

    // ================================================================
    // 记分员：提交（固化快照）
    // ================================================================

    /**
     * 提交记分卡。前置条件：
     * ① 赛事处于「进行中」；
     * ② 当前为草稿状态（重复提交直接拒绝，防止覆盖已在确认/争议链路中的快照）；
     * ③ 应打球洞（当前开放洞）每洞都有 ≥1 的合法杆数；
     * ④ 洞号不重复（DB 唯一约束 + 提交校验双保险）。
     * 通过后固化快照、状态置为「待球员确认」，并追加提交事件。
     */
    @Transactional
    public com.golf.course.dto.ScorecardView submit(Long id, SubmitCardRequest req) {
        Scorecard card = getCard(id);
        checkVersion(card, req.expectedVersion());
        String submitter = req.submitter().trim();
        if (!Dict.CARD_DRAFT.equals(card.getStatus())) {
            throw new BizException("只有草稿状态的记分卡可以提交，当前状态：" + card.getStatus());
        }
        Tournament tournament = card.getTournament();
        if (!"进行中".equals(tournament.getStatus())) {
            throw new BizException("只有「进行中」的赛事可以提交记分卡，当前赛事状态：" + tournament.getStatus());
        }

        List<Hole> open = openHoles();
        if (open.isEmpty()) {
            throw new BizException("当前没有任何「开放」球洞，无法界定应打球洞，不能提交");
        }
        List<ScorecardHole> lines = lines(id);
        Map<Integer, ScorecardHole> byNo = new HashMap<>();
        for (ScorecardHole line : lines) {
            if (byNo.put(line.getHoleNo(), line) != null) {
                throw new BizException("存在重复洞号：第 " + line.getHoleNo() + " 洞，提交前请删除重复行");
            }
        }
        List<String> problems = new ArrayList<>();
        List<Snapshot.Hole> snapshotHoles = new ArrayList<>();
        int total = 0;
        for (Hole hole : open) {
            ScorecardHole line = byNo.get(hole.getHoleNo());
            if (line == null || line.getStrokes() == null) {
                problems.add("第 " + hole.getHoleNo() + " 洞（par " + hole.getPar() + "）尚未录入杆数");
                continue;
            }
            if (line.getStrokes() < Dict.MIN_STROKES) {
                problems.add("第 " + hole.getHoleNo() + " 洞杆数非法（须 ≥ " + Dict.MIN_STROKES + "）");
                continue;
            }
            total += line.getStrokes();
            snapshotHoles.add(new Snapshot.Hole(hole.getHoleNo(), hole.getPar(),
                    line.getStrokes(), null, null, null));
        }
        Set<Integer> openNos = open.stream().map(Hole::getHoleNo).collect(Collectors.toSet());
        for (ScorecardHole extra : lines) {
            if (!openNos.contains(extra.getHoleNo())) {
                problems.add("第 " + extra.getHoleNo() + " 洞当前非开放洞，不属于应打球洞");
            }
        }
        if (!problems.isEmpty()) {
            throw new BizException("记分卡不满足提交条件：" + String.join("；", problems));
        }

        snapshotHoles.sort(Comparator.comparing(Snapshot.Hole::holeNo));
        String beforeJson = card.getSnapshotJson(); // 草稿首次提交为 null（之前从未固化）
        card.setExpectedHoles(open.size());
        card.setTotalStrokes(total);
        card.setSnapshotJson(toJson(new Snapshot(card.getRoundNo(), total, snapshotHoles)));
        card.setStatus(Dict.CARD_PENDING);
        card.setSubmittedAt(LocalDateTime.now());
        card.setLastSubmitter(submitter);
        card.setUpdatedAt(LocalDateTime.now());
        Scorecard saved = cardRepository.saveAndFlush(card);

        recordEvent(saved, Dict.ACTION_SUBMIT, submitter,
                open.size() + " 洞齐全，提交后进入待球员确认",
                Dict.CARD_DRAFT, Dict.CARD_PENDING,
                req.expectedVersion(), saved.getVersion(), beforeJson, saved.getSnapshotJson());
        return getView(id);
    }

    // ================================================================
    // 球员：整卡确认 / 按洞争议
    // ================================================================

    /**
     * 球员整卡确认。仅本人、仅「待球员确认」可操作；一旦「已签核」即终态，
     * 不提供任何撤回入口。确认后该卡快照才成为正式成绩口径。
     */
    @Transactional
    public com.golf.course.dto.ScorecardView confirm(Long id, ConfirmCardRequest req) {
        Scorecard card = getCard(id);
        checkVersion(card, req.expectedVersion());
        requireOwnCard(card, req.playerName());
        if (Dict.CARD_SIGNED.equals(card.getStatus())) {
            throw new BizException("记分卡已签核，签核为终态，不能撤回或重复确认");
        }
        if (!Dict.CARD_PENDING.equals(card.getStatus())) {
            throw new BizException("只有「待球员确认」的记分卡可以整卡确认，当前状态：" + card.getStatus());
        }
        String beforeJson = card.getSnapshotJson();
        card.setStatus(Dict.CARD_SIGNED);
        card.setConfirmedAt(LocalDateTime.now());
        card.setUpdatedAt(LocalDateTime.now());
        Scorecard saved = cardRepository.saveAndFlush(card);

        recordEvent(saved, Dict.ACTION_CONFIRM, req.playerName().trim(),
                "球员整卡确认，记分卡正式签核",
                Dict.CARD_PENDING, Dict.CARD_SIGNED,
                req.expectedVersion(), saved.getVersion(), beforeJson, saved.getSnapshotJson());
        return getView(id);
    }

    /**
     * 球员针对具体球洞填写争议理由。仅本人、仅「待球员确认」可操作；
     * 提交后整卡进入「争议中」，期间该卡成绩不计入正式排行榜。
     * 本次未提及的球洞清空其上一轮争议/裁决痕迹，保证争议集合与本次操作一致。
     */
    @Transactional
    public com.golf.course.dto.ScorecardView dispute(Long id, DisputeRequest req) {
        Scorecard card = getCard(id);
        checkVersion(card, req.expectedVersion());
        requireOwnCard(card, req.playerName());
        if (Dict.CARD_SIGNED.equals(card.getStatus())) {
            throw new BizException("记分卡已签核，不能再提出争议");
        }
        if (!Dict.CARD_PENDING.equals(card.getStatus())) {
            throw new BizException("只有「待球员确认」的记分卡可以提出争议，当前状态：" + card.getStatus());
        }

        List<ScorecardHole> lines = lines(id);
        Map<Integer, ScorecardHole> byNo = lines.stream()
                .collect(Collectors.toMap(ScorecardHole::getHoleNo, l -> l, (a, b) -> a));
        Set<Integer> disputedNos = new HashSet<>();
        List<String> detailHoles = new ArrayList<>();
        for (DisputeRequest.Item item : req.disputes()) {
            if (item.holeNo() == null || item.reason() == null || item.reason().isBlank()) {
                throw new BizException("争议必须包含洞号与争议理由");
            }
            ScorecardHole line = byNo.get(item.holeNo());
            if (line == null) {
                throw new BizException("第 " + item.holeNo() + " 洞不在本张记分卡内");
            }
            if (disputedNos.add(item.holeNo())) {
                line.setDisputeReason(item.reason().trim());
                line.setRuledStrokes(null);
                line.setRulingNote(null);
                line.setUpdatedAt(LocalDateTime.now());
                holeLineRepository.save(line);
                detailHoles.add("第" + item.holeNo() + "洞");
            }
        }
        // 其余洞：上一轮（被发回再确认时）遗留的争议/裁决痕迹全部清掉
        for (ScorecardHole line : lines) {
            if (!disputedNos.contains(line.getHoleNo())
                    && (line.getDisputeReason() != null || line.getRuledStrokes() != null)) {
                line.setDisputeReason(null);
                line.setRuledStrokes(null);
                line.setRulingNote(null);
                line.setUpdatedAt(LocalDateTime.now());
                holeLineRepository.save(line);
            }
        }

        String beforeJson = card.getSnapshotJson();
        String afterJson = snapshotFromLines(card, lines);
        card.setStatus(Dict.CARD_DISPUTED);
        card.setSnapshotJson(afterJson); // 工作快照同步争议标记；正式口径仍只在签核后生效
        card.setUpdatedAt(LocalDateTime.now());
        Scorecard saved = cardRepository.saveAndFlush(card);

        recordEvent(saved, Dict.ACTION_DISPUTE, req.playerName().trim(),
                "球员就 " + String.join("、", detailHoles) + " 提出争议，争议期间成绩不计入正式排行榜",
                Dict.CARD_PENDING, Dict.CARD_DISPUTED,
                req.expectedVersion(), saved.getVersion(), beforeJson, afterJson);
        return getView(id);
    }

    // ================================================================
    // 裁判：维持 / 修正
    // ================================================================

    /**
     * 裁判裁决争议卡：必须逐项覆盖所有争议洞。ruledStrokes 等于原杆数即「维持」，
     * 否则为「修正」；每项必须写裁决说明。裁决后以裁决杆数重新固化快照、
     * 清除争议标记，整卡发回「待球员确认」，由球员再次确认或再争议。
     */
    @Transactional
    public com.golf.course.dto.ScorecardView rule(Long id, RulingRequest req) {
        Scorecard card = getCard(id);
        checkVersion(card, req.expectedVersion());
        if (!Dict.CARD_DISPUTED.equals(card.getStatus())) {
            throw new BizException("裁判只能处理「争议中」的记分卡，当前状态：" + card.getStatus());
        }

        List<ScorecardHole> lines = lines(id);
        Map<Integer, ScorecardHole> byNo = lines.stream()
                .collect(Collectors.toMap(ScorecardHole::getHoleNo, l -> l, (a, b) -> a));
        List<ScorecardHole> disputedLines = lines.stream()
                .filter(l -> l.getDisputeReason() != null && !l.getDisputeReason().isBlank())
                .toList();
        if (disputedLines.isEmpty()) {
            throw new BizException("该卡没有待裁决的争议球洞");
        }
        Set<Integer> ruledNos = new HashSet<>();
        boolean anyAdjusted = false;
        List<String> detailParts = new ArrayList<>();
        for (RulingRequest.Item item : req.rulings()) {
            if (item.ruledStrokes() == null || item.ruledStrokes() < Dict.MIN_STROKES) {
                throw new BizException("第 " + item.holeNo() + " 洞裁决杆数非法（须 ≥ " + Dict.MIN_STROKES + "）");
            }
            if (item.note() == null || item.note().isBlank()) {
                throw new BizException("第 " + item.holeNo() + " 洞必须填写裁决说明");
            }
            ScorecardHole line = byNo.get(item.holeNo());
            if (line == null || line.getDisputeReason() == null || line.getDisputeReason().isBlank()) {
                throw new BizException("第 " + item.holeNo() + " 洞不是本次争议洞，裁判裁决只能针对争议洞");
            }
            if (!ruledNos.add(item.holeNo())) {
                throw new BizException("第 " + item.holeNo() + " 洞被重复裁决");
            }
            boolean adjusted = !Objects.equals(line.getStrokes(), item.ruledStrokes());
            anyAdjusted |= adjusted;
            line.setRuledStrokes(item.ruledStrokes());
            line.setRulingNote(item.note().trim());
            // 重新固化时以裁决杆数为正式工作杆数，争议标记清除（裁决说明留痕）
            line.setStrokes(item.ruledStrokes());
            line.setDisputeReason(null);
            line.setUpdatedAt(LocalDateTime.now());
            holeLineRepository.save(line);
            detailParts.add("第" + item.holeNo() + "洞" + (adjusted ? "修正为" + item.ruledStrokes() + "杆" : "维持原成绩"));
        }
        Set<Integer> openDisputed = disputedLines.stream()
                .map(ScorecardHole::getHoleNo).collect(Collectors.toSet());
        openDisputed.removeAll(ruledNos);
        if (!openDisputed.isEmpty()) {
            throw new BizException("仍有争议洞未裁决："
                    + openDisputed.stream().sorted().map(n -> "第" + n + "洞").collect(Collectors.joining("、")));
        }

        String beforeJson = card.getSnapshotJson();
        int total = lines.stream().mapToInt(ScorecardHole::getStrokes).sum();
        String afterJson = snapshotFromLines(card, lines);
        card.setTotalStrokes(total);
        card.setSnapshotJson(afterJson);
        card.setStatus(Dict.CARD_PENDING);
        card.setLastSubmitter(req.referee().trim());
        card.setUpdatedAt(LocalDateTime.now());
        Scorecard saved = cardRepository.saveAndFlush(card);

        String action = anyAdjusted ? Dict.ACTION_ADJUST : Dict.ACTION_UPHOLD;
        recordEvent(saved, action, req.referee().trim(),
                "裁判裁决：" + String.join("、", detailParts) + "；发回球员重新确认",
                Dict.CARD_DISPUTED, Dict.CARD_PENDING,
                req.expectedVersion(), saved.getVersion(), beforeJson, afterJson);
        return getView(id);
    }

    // ================================================================
    // 正式排行榜 & 赛事收口检查
    // ================================================================

    /**
     * 正式排行榜：只解析「已签核」卡的当前固化快照，按总杆升序。
     * 草稿 / 待确认 / 争议中全部缺席；历史事件快照不参与，杜绝重复计入。
     */
    @Transactional(readOnly = true)
    public List<OfficialResult> officialResults(Long tournamentId) {
        return cardRepository.findByTournamentIdOrderByRoundNoAscIdAsc(tournamentId).stream()                .filter(c -> Dict.CARD_SIGNED.equals(c.getStatus()))
                .map(this::toOfficialResult)
                .sorted(Comparator.comparing(OfficialResult::totalStrokes))
                .toList();
    }

    /**
     * 赛事从「进行中」推进到「已结束」前的强制检查：
     * 每个参赛名额的每个应打轮次都必须有一张已签核卡；缺卡、草稿、待确认、争议中
     * 全部逐条列出并阻止收口。
     */
    @Transactional(readOnly = true)
    public SignoffCheck signoffCheck(Long tournamentId) {
        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BizException("赛事不存在：" + tournamentId));
        List<TournamentEntry> entries = entryRepository.findByTournamentIdOrderByIdAsc(tournamentId);
        List<String> reasons = new ArrayList<>();
        int signedRounds = 0;
        for (TournamentEntry entry : entries) {
            for (int round = 1; round <= entry.getRoundCount(); round++) {
                Scorecard card = cardRepository
                        .findByTournamentIdAndPlayerIdAndRoundNo(tournamentId, entry.getPlayer().getId(), round)
                        .orElse(null);
                if (card == null) {
                    reasons.add("球员「" + entry.getPlayer().getName() + "」第 " + round + " 轮尚无记分卡（草稿都未创建）");
                    continue;
                }
                String status = card.getStatus();
                if (Dict.CARD_SIGNED.equals(status)) {
                    signedRounds++;
                } else if (Dict.CARD_DRAFT.equals(status)) {
                    reasons.add("球员「" + entry.getPlayer().getName() + "」第 " + round
                            + " 轮记分卡仍是草稿，记分员尚未提交");
                } else if (Dict.CARD_PENDING.equals(status)) {
                    reasons.add("球员「" + entry.getPlayer().getName() + "」第 " + round
                            + " 轮记分卡待球员确认");
                } else if (Dict.CARD_DISPUTED.equals(status)) {
                    reasons.add("球员「" + entry.getPlayer().getName() + "」第 " + round
                            + " 轮记分卡存在争议，等待裁判裁决/球员再确认");
                } else {
                    reasons.add("球员「" + entry.getPlayer().getName() + "」第 " + round
                            + " 轮记分卡状态未知：" + status);
                }
            }
        }
        if (entries.isEmpty()) {
            reasons.add("赛事「" + tournament.getName() + "」没有任何参赛球员，无法收口");
        }
        return new SignoffCheck(reasons.isEmpty(), entries.size(), signedRounds, reasons);
    }

    // ================================================================
    // 内部辅助
    // ================================================================

    private Scorecard getCard(Long id) {
        return cardRepository.findById(id)
                .orElseThrow(() -> new BizException("记分卡不存在：" + id));
    }

    private List<ScorecardHole> lines(Long cardId) {
        return holeLineRepository.findByScorecardIdOrderByHoleNoAsc(cardId);
    }

    private Player requirePlayer(Long playerId) {
        if (playerId == null) {
            throw new BizException("必须指定球员");
        }
        return playerRepository.findById(playerId)
                .orElseThrow(() -> new BizException("球员不存在：" + playerId));
    }

    private List<Hole> openHoles() {
        return holeRepository.findByStatus("开放").stream()
                .sorted(Comparator.comparing(Hole::getHoleNo))
                .toList();
    }

    /** 球员姓名必须与卡面球员一致（本系统无登录态，以显式姓名做最小归属校验）。 */
    private void requireOwnCard(Scorecard card, String playerName) {
        if (playerName == null || playerName.isBlank()) {
            throw new BizException("球员姓名不能为空");
        }
        if (!card.getPlayer().getName().equals(playerName.trim())) {
            throw new BizException("球员只能处理本人的记分卡，该卡属于「" + card.getPlayer().getName() + "」");
        }
    }

    /**
     * 乐观并发闸门：页面必须基于最新版本操作。版本不一致直接 409，
     * 消息带当前状态，前端据此提示「数据已变化」并刷新，杜绝静默覆盖。
     * JPA {@code @Version} 在提交时还会再兜一层（见 GlobalExceptionHandler）。
     */
    private void checkVersion(Scorecard card, Long expectedVersion) {
        if (expectedVersion == null) {
            throw new ConflictException("缺少版本号，请刷新页面后基于最新状态操作（当前状态："
                    + card.getStatus() + "，最新版本 v" + card.getVersion() + "）");
        }
        if (!expectedVersion.equals(card.getVersion())) {
            throw new ConflictException("记分卡数据已变化：你打开的是 v" + expectedVersion
                    + "，当前已是 v" + card.getVersion() + "（状态：" + card.getStatus()
                    + "）。本次操作未生效，请刷新页面查看最新状态后再操作");
        }
    }

    private String snapshotFromLines(Scorecard card, List<ScorecardHole> lines) {
        List<Snapshot.Hole> holes = lines.stream()
                .sorted(Comparator.comparing(ScorecardHole::getHoleNo))
                .map(l -> new Snapshot.Hole(l.getHoleNo(), l.getPar(), l.getStrokes(),
                        l.getDisputeReason(), l.getRuledStrokes(), l.getRulingNote()))
                .toList();
        int total = lines.stream().filter(l -> l.getStrokes() != null)
                .mapToInt(ScorecardHole::getStrokes).sum();
        return toJson(new Snapshot(card.getRoundNo(), total, holes));
    }

    private OfficialResult toOfficialResult(Scorecard card) {
        Snapshot snapshot = readSnapshot(card.getSnapshotJson(), card.getId());
        int total = snapshot.totalStrokes() == null ? 0 : snapshot.totalStrokes();
        int totalPar = snapshot.holes().stream().mapToInt(Snapshot.Hole::par).sum();
        return new OfficialResult(card.getPlayer().getId(), card.getPlayer().getName(),
                card.getRoundNo(), card.getId(), card.getVersion(), total, totalPar, total - totalPar);
    }

    private void recordEvent(Scorecard card, String action, String actor, String detail,
                             String beforeStatus, String afterStatus,
                             Long beforeVersion, Long afterVersion,
                             String beforeJson, String afterJson) {
        ScorecardEvent event = new ScorecardEvent();
        event.setScorecard(card);
        event.setAction(action);
        event.setActor(actor);
        event.setDetail(detail);
        event.setBeforeStatus(beforeStatus);
        event.setAfterStatus(afterStatus);
        event.setBeforeVersion(beforeVersion);
        event.setAfterVersion(afterVersion);
        event.setBeforeJson(beforeJson);
        event.setAfterJson(afterJson);
        event.setCreatedAt(LocalDateTime.now());
        eventRepository.save(event);
    }

    private String toJson(Snapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new BizException("快照序列化失败：" + e.getMessage());
        }
    }

    private Snapshot readSnapshot(String json, Long cardId) {
        try {
            return objectMapper.readValue(json, Snapshot.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new BizException("记分卡 " + cardId + " 的固化快照无法解析");
        }
    }
}
