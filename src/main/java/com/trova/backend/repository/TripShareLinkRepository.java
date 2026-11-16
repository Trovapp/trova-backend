package com.trova.backend.repository;

import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripShareLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TripShareLinkRepository extends JpaRepository<TripShareLink, Long> {
    Optional<TripShareLink> findFirstByTripAndRevokedAtIsNullOrderByCreatedAtAsc(Trip trip);

    List<TripShareLink> findByTripAndRevokedAtIsNull(Trip trip);

    Optional<TripShareLink> findByTokenAndRevokedAtIsNull(String token);

    void deleteByTrip(Trip trip);
}
