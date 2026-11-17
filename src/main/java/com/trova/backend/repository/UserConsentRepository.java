package com.trova.backend.repository;

import com.trova.backend.entity.ConsentType;
import com.trova.backend.entity.User;
import com.trova.backend.entity.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO user_consents (user_id, type, version, agreed_at)
            VALUES (:userId, :type, :version, :agreedAt)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("userId") Long userId, @Param("type") String type,
            @Param("version") String version, @Param("agreedAt") LocalDateTime agreedAt);

    boolean existsByUserAndTypeAndVersion(User user, ConsentType type, String version);

    boolean existsByUserAndType(User user, ConsentType type);

    List<UserConsent> findByUser(User user);

    void deleteByUser(User user);
}
