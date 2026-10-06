package com.golf.course.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.golf.course.common.BizException;
import com.golf.course.common.Dict;
import com.golf.course.entity.Player;
import com.golf.course.entity.TeeTime;
import com.golf.course.repository.PlayerRepository;
import com.golf.course.repository.TeeTimeRepository;

/**
 * 开球时间业务。核心规则：① 预订必须指定存在的球员；② 同一球员在同一天、同一时段不可重复预订
 * （唯一约束 + 业务前置校验，防止并发竞态）；③ 状态只能是 可订 / 已订。
 */
@Service
public class TeeTimeService {

    private final TeeTimeRepository teeTimeRepository;
    private final PlayerRepository playerRepository;

    public TeeTimeService(TeeTimeRepository teeTimeRepository, PlayerRepository playerRepository) {
        this.teeTimeRepository = teeTimeRepository;
        this.playerRepository = playerRepository;
    }

    public List<TeeTime> list(String status, String date) {
        if (date != null && !date.isBlank()) {
            return teeTimeRepository.findByPlayDate(java.time.LocalDate.parse(date));
        }
        if (status != null && !status.isBlank()) {
            return teeTimeRepository.findByStatus(status);
        }
        return teeTimeRepository.findAll();
    }

    public TeeTime get(Long id) {
        return teeTimeRepository.findById(id).orElseThrow(() -> new BizException("开球时间不存在：" + id));
    }

    /** 预订：默认状态 已订 */
    @Transactional
    public TeeTime book(TeeTime input) {
        Player player = resolvePlayer(input.getPlayer());
        if (input.getPlayDate() == null) {
            throw new BizException("打球日期不能为空");
        }
        if (input.getSlot() == null || input.getSlot().isBlank()) {
            throw new BizException("开球时段不能为空");
        }
        // 规则②：同一球员同一天同一时段不可重复预订
        if (!teeTimeRepository.findByPlayerIdAndPlayDateAndSlot(
                player.getId(), input.getPlayDate(), input.getSlot()).isEmpty()) {
            throw new BizException("球员 " + player.getName() + " 在 " + input.getPlayDate()
                    + " " + input.getSlot() + " 已存在预订，不可重复预订");
        }
        String status = input.getStatus();
        if (!Dict.isValidTeeStatus(status)) {
            status = "已订";
        }
        TeeTime tee = new TeeTime();
        tee.setPlayDate(input.getPlayDate());
        tee.setPlayer(player);
        tee.setSlot(input.getSlot());
        tee.setStatus(status);
        tee.setNote(input.getNote());
        tee.setCreatedAt(LocalDateTime.now());
        return teeTimeRepository.save(tee);
    }

    @Transactional
    public TeeTime update(Long id, TeeTime input) {
        TeeTime tee = get(id);
        Player player = resolvePlayer(input.getPlayer());
        if (input.getPlayDate() != null) {
            tee.setPlayDate(input.getPlayDate());
        }
        if (input.getSlot() != null && !input.getSlot().isBlank()) {
            tee.setSlot(input.getSlot());
        }
        tee.setPlayer(player);
        // 改期 / 改时段后重新校验重复预订
        if (!teeTimeRepository.findByPlayerIdAndPlayDateAndSlot(
                player.getId(), tee.getPlayDate(), tee.getSlot()).stream()
                .noneMatch(t -> t.getId().equals(id))) {
            throw new BizException("球员 " + player.getName() + " 在 " + tee.getPlayDate()
                    + " " + tee.getSlot() + " 已存在预订，不可重复预订");
        }
        if (input.getStatus() != null && !input.getStatus().isBlank()) {
            if (!Dict.isValidTeeStatus(input.getStatus())) {
                throw new BizException("开球时间状态不合法：" + input.getStatus());
            }
            tee.setStatus(input.getStatus());
        }
        if (input.getNote() != null) {
            tee.setNote(input.getNote());
        }
        return teeTimeRepository.save(tee);
    }

    /** 释放：已订 → 可订，相当于取消该预订把时段腾出 */
    @Transactional
    public TeeTime release(Long id) {
        TeeTime tee = get(id);
        tee.setStatus("可订");
        return teeTimeRepository.save(tee);
    }

    private Player resolvePlayer(Player player) {
        if (player == null || player.getId() == null) {
            throw new BizException("必须指定球员");
        }
        return playerRepository.findById(player.getId())
                .orElseThrow(() -> new BizException("球员不存在：" + player.getId()));
    }
}
