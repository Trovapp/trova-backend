package com.trova.backend.repository;

import com.trova.backend.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> lockById(@Param("id") Long id);

    Optional<User> findByProviderAndProviderUserId(String provider, String providerUserId);

    @Query("select u.id from User u where u.suspendedAt is not null")
    List<Long> findSuspendedIds();
}
