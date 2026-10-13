package com.trova.backend.repository;

import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByProviderAndProviderUserId(String provider, String providerUserId);

    @Query("select u.id from User u where u.suspendedAt is not null")
    List<Long> findSuspendedIds();
}
