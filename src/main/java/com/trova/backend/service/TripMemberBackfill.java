package com.trova.backend.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TripMemberBackfill implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    public TripMemberBackfill(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.update("""
                insert into trip_members (trip_id, user_id, role, joined_at)
                select t.id, t.user_id, 'OWNER', t.created_at from trips t
                where not exists (select 1 from trip_members m where m.trip_id=t.id and m.user_id=t.user_id)
                """);
    }
}
