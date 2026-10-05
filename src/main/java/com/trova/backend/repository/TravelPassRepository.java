package com.trova.backend.repository;

import com.trova.backend.entity.TravelPass;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TravelPassRepository extends JpaRepository<TravelPass, Long> {
    Optional<TravelPass> findByTransactionId(String transactionId);

    Optional<TravelPass> findFirstByUserOrderByExpiresAtDesc(User user);

    java.util.List<TravelPass> findByUser(User user);

    void deleteByUser(User user);
}
