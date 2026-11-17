package com.trova.backend.service;

import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripShareLink;
import com.trova.backend.repository.TripShareLinkRepository;
import com.trova.backend.security.SuspendedUsers;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
public class TripShareLinkService {

    private final TripShareLinkRepository repository;
    private final SuspendedUsers suspendedUsers;
    private final String webAppUrl;
    private final SecureRandom random = new SecureRandom();

    public TripShareLinkService(
            TripShareLinkRepository repository, SuspendedUsers suspendedUsers, @Value("${app.web-app-url:}") String webAppUrl
    ) {
        this.repository = repository;
        this.suspendedUsers = suspendedUsers;
        this.webAppUrl = webAppUrl.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public Optional<TripShareLink> getActive(Trip trip) {
        return repository.findFirstByTripAndRevokedAtIsNullOrderByCreatedAtAsc(trip);
    }

    public record CreateResult(TripShareLink link, boolean created) {
    }

    @Transactional
    public CreateResult createOrGet(Trip trip) {
        return repository.findFirstByTripAndRevokedAtIsNullOrderByCreatedAtAsc(trip)
                .map(link -> new CreateResult(link, false))
                .orElseGet(() -> {
                    byte[] bytes = new byte[32];
                    random.nextBytes(bytes);
                    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                    return new CreateResult(repository.save(new TripShareLink(trip, token)), true);
                });
    }

    @Transactional
    public boolean revoke(Trip trip) {
        List<TripShareLink> links = repository.findByTripAndRevokedAtIsNull(trip);
        links.forEach(TripShareLink::revoke);
        return !links.isEmpty();
    }

    @Transactional(readOnly = true)
    public Optional<Trip> findPublic(String token) {
        return repository.findByTokenAndRevokedAtIsNull(token)
                .map(link -> {
                    Trip trip = link.getTrip();
                    // open-in-view가 꺼져 있어 반환 전에 여행의 공개 필드를 읽을 수 있게 초기화한다.
                    Hibernate.initialize(trip);
                    return trip;
                })
                .filter(trip -> !suspendedUsers.isSuspended(trip.getUser().getId()));
    }

    public String url(String token) {
        return webAppUrl + "/t/" + token;
    }
}
