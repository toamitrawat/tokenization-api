package com.example.tokenization.repository;

import com.example.tokenization.entity.CardToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data repository for CardToken entities.
 */
public interface CardTokenRepository extends JpaRepository<CardToken, Long> {
    Optional<CardToken> findByToken(String token);
    Optional<CardToken> findByPanHash(String panHash);
}
