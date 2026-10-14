package com.trova.backend.repository;

import com.trova.backend.entity.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    List<PushSubscription> findByUserId(Long userId);

    List<PushSubscription> findByUserIdOrderByLastSeenAtDescIdDesc(Long userId);

    Optional<PushSubscription> findByEndpoint(String endpoint);

    @Transactional
    void deleteByUserId(Long userId);

    @Transactional
    void deleteByEndpointAndUserId(String endpoint, Long userId);

    /** 발송 성공 시각만 — 보내는 동안 다른 사람에게 옮겨진 구독은 건드리지 않는다(엔티티 merge는 옛 값으로 덮었다). */
    @Modifying
    @Transactional
    @Query("update PushSubscription s set s.lastSuccessAt = :at where s.id = :id and s.userId = :userId")
    int markSuccess(@Param("id") Long id, @Param("userId") Long userId, @Param("at") LocalDateTime at);

    @Transactional
    void deleteByIdAndUserId(Long id, Long userId);
}
