package com.golf.course.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.Player;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    Optional<Player> findByPlayerNo(String playerNo);
}
