package com.trova.backend.events;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class TripAudience {
    private final JdbcTemplate jdbc;
    TripAudience(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    long version(long trip, long user) {
        return jdbc.queryForList("""
                select t.version from trips t join trip_members m on t.id=m.trip_id
                join users u on u.id=m.user_id
                where t.id=? and m.user_id=? and u.suspended_at is null
                """, Long.class, trip, user).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
    }

    List<Long> members(long trip) {
        return jdbc.queryForList("""
                select m.user_id from trip_members m join users u on u.id=m.user_id
                where m.trip_id=? and u.suspended_at is null
                """, Long.class, trip);
    }
}
