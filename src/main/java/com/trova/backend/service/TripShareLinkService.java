package com.trova.backend.service;

import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import com.trova.backend.repository.TripRepository;
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
    private final TripRepository trips;
    private final TripChanges changes;
    private final SuspendedUsers suspendedUsers;
    private final String webAppUrl;
    private final SecureRandom random = new SecureRandom();

    public TripShareLinkService(
            TripRepository trips, TripShareLinkRepository repository, TripChanges changes, SuspendedUsers suspendedUsers, @Value("${app.web-app-url:}") String webAppUrl
    ) {
        this.trips = trips;
        this.changes = changes;
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
    public CreateResult createOrGet(Trip trip, User actor) {
        Trip locked = trips.lockById(trip.getId()).orElseThrow();
        TripAccess.requireMember(locked, actor);
        Optional<TripShareLink> active = getActive(locked);
        if (active.isPresent()) {
            changes.returnVersionAfterCommit(locked);
            return new CreateResult(active.get(), false);
        }
        TripAccess.requireOwner(locked, actor);
        changes.record(locked, actor, "LINK_CREATED", "공유 링크를 만들었어요");
        return create(locked);
    }

    private CreateResult create(Trip trip) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new CreateResult(repository.save(new TripShareLink(trip, token)), true);
    }

    @Transactional
    public boolean revoke(Trip trip) {
        Trip locked = trips.lockById(trip.getId()).orElseThrow();
        if (!revokeActive(locked)) return false;
        changes.record(locked, locked.getUser(), "LINK_REVOKED", "공유 링크를 껐어요");
        return true;
    }

    private boolean revokeActive(Trip trip) {
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

    public Optional<Trip> lockTripForToken(String token) {
        return trips.lockForToken(token);
    }

    @Transactional
    public TripShareLink rotate(Trip trip) {
        Trip locked = trips.lockById(trip.getId()).orElseThrow();
        revokeActive(locked);
        changes.record(locked, locked.getUser(), "LINK_ROTATED", "공유 링크를 새로 만들었어요");
        return create(locked).link();
    }

    public String url(String token) {
        return webAppUrl + "/t/" + token;
    }
}
