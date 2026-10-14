package com.trova.backend.repository;

import com.trova.backend.entity.ExpoPushToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ExpoPushTokenRepository extends JpaRepository<ExpoPushToken, Long> {

    List<ExpoPushToken> findByUserId(Long userId);

    List<ExpoPushToken> findByUserIdOrderByLastSeenAtDescIdDesc(Long userId);

    Optional<ExpoPushToken> findByToken(String token);

    @Transactional
    void deleteByUserId(Long userId);

    @Transactional
    void deleteByTokenAndUserId(String token, Long userId);

    @Transactional
    void deleteByIdAndUserId(Long id, Long userId);

    /** 발송 성공 시각만 — 보내는 동안 다른 사람에게 옮겨진 토큰은 건드리지 않는다(웹 구독과 같은 이유). */
    @Modifying
    @Transactional
    @Query("update ExpoPushToken t set t.lastSuccessAt = :at where t.id = :id and t.userId = :userId")
    int markSuccess(@Param("id") Long id, @Param("userId") Long userId, @Param("at") LocalDateTime at);
}
