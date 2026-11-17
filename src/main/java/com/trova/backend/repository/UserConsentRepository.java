package com.trova.backend.repository;

import com.trova.backend.entity.ConsentType;
import com.trova.backend.entity.User;
import com.trova.backend.entity.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {

    boolean existsByUserAndTypeAndVersion(User user, ConsentType type, String version);

    boolean existsByUserAndType(User user, ConsentType type);

    List<UserConsent> findByUser(User user);

    void deleteByUser(User user);
}
