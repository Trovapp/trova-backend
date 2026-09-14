package com.trova.backend.repository;

import com.trova.backend.entity.User;
import com.trova.backend.entity.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {
    Optional<UserPreference> findByUserAndMood(User user, String mood);
    List<UserPreference> findByUser(User user);
}
