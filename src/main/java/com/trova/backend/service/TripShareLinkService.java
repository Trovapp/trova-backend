package com.trova.backend.service;

import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripShareLink;
import com.trova.backend.repository.TripShareLinkRepository;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

@Service
public class TripShareLinkService {

    private final TripShareLinkRepository repository;
    private final String webAppUrl;
    private final SecureRandom random = new SecureRandom();

    public TripShareLinkService(
            TripShareLinkRepository repository, @Value("${app.web-app-url:}") String webAppUrl
    ) {
        this.repository = repository;
        this.webAppUrl = webAppUrl.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public Optional<TripShareLink> getActive(Trip trip) {
        return repository.findByTripAndRevokedAtIsNull(trip);
    }

    @Transactional
    public TripShareLink createOrGet(Trip trip) {
        return repository.findByTripAndRevokedAtIsNull(trip).orElseGet(() -> {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            return repository.save(new TripShareLink(trip, token));
        });
    }

    @Transactional
    public boolean revoke(Trip trip) {
        return repository.findByTripAndRevokedAtIsNull(trip)
                .map(link -> {
                    link.revoke();
                    return true;
                })
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public Optional<Trip> findPublic(String token) {
        return repository.findByTokenAndRevokedAtIsNull(token)
                .map(link -> {
                    Trip trip = link.getTrip();
                    // open-in-view가 꺼져 있어 반환 전에 여행의 공개 필드를 읽을 수 있게 초기화한다.
                    Hibernate.initialize(trip);
                    return trip;
                });
    }

    public String url(String token) {
        return webAppUrl + "/t/" + token;
    }
}
