package com.trova.backend.repository;

import com.trova.backend.entity.ShareKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShareKeyRepository extends JpaRepository<ShareKey, Long> {
    Optional<ShareKey> findByUserId(Long userId);

    Optional<ShareKey> findByKeyHash(String keyHash);

    void deleteByUserId(Long userId);
}
