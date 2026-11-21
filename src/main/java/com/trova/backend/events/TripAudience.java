package com.trova.backend.events;

import java.util.ArrayList;
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

    record Snapshot(long version, List<Long> members) {}

    Snapshot snapshot(long trip) {
        return jdbc.query("""
                select t.version, m.user_id from trips t join trip_members m on m.trip_id=t.id
                join users u on u.id=m.user_id where t.id=? and u.suspended_at is null
                """, rows -> {
                    long version = 0;
                    var members = new ArrayList<Long>();
                    while (rows.next()) {
                        version = rows.getLong(1);
                        members.add(rows.getLong(2));
                    }
                    return new Snapshot(version, members);
                }, trip);
    }

    List<Long> members(long trip) {
        return snapshot(trip).members();
    }
}
