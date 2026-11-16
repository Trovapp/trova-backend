package com.trova.backend.repository;

import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripShareLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TripShareLinkRepository extends JpaRepository<TripShareLink, Long> {
    Optional<TripShareLink> findByTripAndRevokedAtIsNull(Trip trip);

    Optional<TripShareLink> findByTokenAndRevokedAtIsNull(String token);

    void deleteByTrip(Trip trip);
}
