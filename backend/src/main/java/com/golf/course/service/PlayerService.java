package com.golf.course.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.golf.course.common.BizException;
import com.golf.course.common.Dict;
import com.golf.course.entity.Player;
import com.golf.course.repository.PlayerRepository;

/**
 * 球员业务。核心规则：① 球员编号唯一；② 差点 handicap 必须落在 0 ~ 54 之间。
 */
@Service
public class PlayerService {

    private final PlayerRepository playerRepository;

    public PlayerService(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    public List<Player> list() {
        return playerRepository.findAll();
    }

    public Player get(Long id) {
        return playerRepository.findById(id).orElseThrow(() -> new BizException("球员不存在：" + id));
    }

    @Transactional
    public Player create(Player input) {
        if (input.getPlayerNo() == null || input.getPlayerNo().isBlank()) {
            throw new BizException("球员编号不能为空");
        }
        playerRepository.findByPlayerNo(input.getPlayerNo().trim()).ifPresent(p -> {
            throw new BizException("球员编号已存在：" + input.getPlayerNo());
        });
        if (input.getName() == null || input.getName().isBlank()) {
            throw new BizException("球员姓名不能为空");
        }
        if (!Dict.isValidHandicap(input.getHandicap())) {
            throw new BizException("差点须落在 0 ~ 54 之间，当前：" + input.getHandicap());
        }
        Player player = new Player();
        player.setPlayerNo(input.getPlayerNo().trim());
        player.setName(input.getName().trim());
        player.setHandicap(input.getHandicap());
        player.setPhone(input.getPhone());
        player.setNote(input.getNote());
        player.setCreatedAt(LocalDateTime.now());
        return playerRepository.save(player);
    }

    @Transactional
    public Player update(Long id, Player input) {
        Player player = get(id);
        if (input.getName() != null && !input.getName().isBlank()) {
            player.setName(input.getName().trim());
        }
        if (input.getHandicap() != null) {
            if (!Dict.isValidHandicap(input.getHandicap())) {
                throw new BizException("差点须落在 0 ~ 54 之间，当前：" + input.getHandicap());
            }
            player.setHandicap(input.getHandicap());
        }
        if (input.getPhone() != null) {
            player.setPhone(input.getPhone());
        }
        if (input.getNote() != null) {
            player.setNote(input.getNote());
        }
        return playerRepository.save(player);
    }
}
