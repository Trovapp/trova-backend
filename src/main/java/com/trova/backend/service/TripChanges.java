package com.trova.backend.service;

import com.trova.backend.events.TripChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.event.TransactionalEventListener;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TripChanges {
    public static final String VERSION_HEADER = "X-Trip-Version";
    public static final String CONFLICT_TRIP = TripChanges.class.getName();
    private final JdbcTemplate jdbc;

    private final ApplicationEventPublisher publisher;
    public TripChanges(JdbcTemplate jdbc, ApplicationEventPublisher publisher) {
        this.jdbc = jdbc;
        this.publisher = publisher;
    }

    public static class VersionConflict extends RuntimeException {}

    public void checkVersion(Trip trip, long version) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            request.setAttribute(CONFLICT_TRIP, trip.getId());
            String expected = request.getHeader("If-Match");
            if (expected != null && !expected.equals("\"" + version + "\"")) {
                if (!expected.matches("\"[0-9]+\"")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                throw new VersionConflict();
            }
        }
    }

    // JPA가 flush/commit에서 할당한 버전만 보낸다. 204 응답도 같은 헤더를 사용한다.
    public void returnVersionAfterCommit(Trip trip) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return;
        HttpServletResponse response = attributes.getResponse();
        if (response == null) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                response.setHeader(VERSION_HEADER, Long.toString(trip.getVersion()));
            }
        });
    }

    public void record(Trip trip, User actor, String type, String summary) {
        trip.touch();
        returnVersionAfterCommit(trip);
        jdbc.update("""
                insert into trip_activity (trip_id, actor_id, type, summary, created_at)
                values (?, ?, ?, ?, current_timestamp)
                """, trip.getId(), actor.getId(), type, summary);
        jdbc.update("""
                delete from trip_activity where trip_id=? and id in
                (select id from trip_activity where trip_id=? order by id desc offset 100 rows)
                """, trip.getId(), trip.getId());
        publisher.publishEvent(new Recorded(trip, actor.getId(), actor.getNickname(), type, summary));
    }

    private record Deletion(TripChangedEvent event) {}

    public void recordDeletion(Trip trip, User owner) {
        var members = trip.getMembers().stream().map(m -> m.getUser()).filter(user -> !user.isSuspended())
                .map(User::getId).toList();
        publisher.publishEvent(new Deletion(new TripChangedEvent(trip.getId(), trip.getVersion() + 1,
                owner.getId(), owner.getNickname(), TripChangedEvent.TRIP_DELETED, "여행을 삭제했어요", members)));
    }

    @TransactionalEventListener
    public void deleted(Deletion deletion) {
        publisher.publishEvent(deletion.event());
    }

    record Recorded(Trip trip, long actorId, String actorName, String type, String summary) {}

    @TransactionalEventListener
    public void committed(Recorded change) {
        Trip trip = change.trip();
        publisher.publishEvent(new TripChangedEvent(trip.getId(), trip.getVersion(), change.actorId(),
                change.actorName(), change.type(), change.summary()));
    }
}
