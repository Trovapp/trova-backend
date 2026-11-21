package com.trova.backend.service;

import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TripChanges {
    public static final String CONFLICT_TRIP = TripChanges.class.getName();
    private final JdbcTemplate jdbc;

    public TripChanges(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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

    public void record(Trip trip, User actor, String type, String summary) {
        trip.touch();
        jdbc.update("""
                insert into trip_activity (trip_id, actor_id, type, summary, created_at)
                values (?, ?, ?, ?, current_timestamp)
                """, trip.getId(), actor.getId(), type, summary);
        jdbc.update("""
                delete from trip_activity where trip_id=? and id in
                (select id from trip_activity where trip_id=? order by id desc offset 100 rows)
                """, trip.getId(), trip.getId());
    }
}
