package com.trova.backend.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.*;
import com.trova.backend.repository.*;
import com.trova.backend.security.JwtService;
import com.trova.backend.service.TripMembershipService;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:trip-events;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "app.web-app-url=https://pwa.example.test"})
@org.springframework.test.context.event.RecordApplicationEvents
@AutoConfigureMockMvc
class TripEventsHttpTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired TripRepository trips;
    @Autowired JwtService jwt;
    @Autowired TripMembershipService memberships;
    @Autowired TripStreams streams;

    @Autowired org.springframework.test.context.event.ApplicationEvents events;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired com.trova.backend.service.TripChanges changes;

    @Test void publishesCommittedVersionAndDiscardsRolledBackChanges() {
        var owner = user();
        long id = trips.saveAndFlush(new Trip(owner, "여행", LocalDate.now(), LocalDate.now())).getId();
        transactions.executeWithoutResult(tx -> {
            changes.record(trips.findById(id).orElseThrow(), owner, "TRIP_TITLE", "제목 변경");
            tx.setRollbackOnly();
        });
        assertThat(events.stream(TripChangedEvent.class)).isEmpty();
        transactions.executeWithoutResult(tx ->
                changes.record(trips.findById(id).orElseThrow(), owner, "TRIP_TITLE", "제목 변경"));
        assertThat(events.stream(TripChangedEvent.class)).containsExactly(new TripChangedEvent(id,
                trips.findById(id).orElseThrow().getVersion(), owner.getId(), owner.getNickname(), "TRIP_TITLE", "제목 변경"));
    }

    User user() { return users.save(new User("google", UUID.randomUUID().toString(), "친구", null)); }

    @Test void pwaCanReadVersionAndRetryDelay() throws Exception {
        var response = mvc.perform(get("/api/trips").header("Origin", "https://pwa.example.test")
                        .header("Authorization", "Bearer " + jwt.issue(user())))
                .andExpect(header().string("Access-Control-Allow-Origin", "https://pwa.example.test"))
                .andReturn().getResponse();
        assertThat(response.getHeader("Access-Control-Expose-Headers").split(", *"))
                .contains("X-Trip-Version", "Retry-After");
    }

    @Test void nativeAndTicketStreamsRequireCurrentMembership() throws Exception {
        var owner = user();
        var editor = user();
        var outsider = user();
        var trip = new Trip(owner, "여행", LocalDate.now(), LocalDate.now());
        trip.getMembers().add(new TripMember(editor, TripMember.Role.EDITOR));
        trips.saveAndFlush(trip);
        String path = "/api/trips/" + trip.getId() + "/events";
        String token = "Bearer " + jwt.issue(editor);
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer " + jwt.issue(outsider))).andExpect(status().isForbidden());
        var nativeStream = mvc.perform(get(path).header("Authorization", token))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(nativeStream.getResponse().getContentAsString()).contains("event:ready", "version");
        mvc.perform(patch("/api/trips/{id}/title", trip.getId())
                        .header("Authorization", "Bearer " + jwt.issue(owner))
                        .contentType("application/json").content("{\"title\":\"새 여행\"}"))
                .andExpect(status().isOk());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(nativeStream.getResponse().getContentAsString()).contains("event:changed", "TRIP_TITLE"));
        String first = ticket(path, token);
        mvc.perform(get(path).param("ticket", first)).andExpect(request().asyncStarted());
        mvc.perform(get(path).param("ticket", first)).andExpect(status().isUnauthorized());
        String outstanding = ticket(path, token);
        memberships.remove(trip.getId(), editor.getId(), owner);
        assertThat(streams.viewing(trip.getId(), editor.getId())).isFalse();
        mvc.perform(get(path).param("ticket", outstanding)).andExpect(status().isForbidden());
        mvc.perform(post(path + "/ticket").header("Authorization", token)).andExpect(status().isForbidden());
    }

    @Test void accountSuspensionInvalidatesOutstandingTicket() throws Exception {
        var owner = user();
        var trip = trips.saveAndFlush(new Trip(owner, "여행", LocalDate.now(), LocalDate.now()));
        String path = "/api/trips/" + trip.getId() + "/events";
        String outstanding = ticket(path, "Bearer " + jwt.issue(owner));
        owner.suspend();
        users.saveAndFlush(owner);
        mvc.perform(get(path).param("ticket", outstanding)).andExpect(status().isForbidden());
    }

    private String ticket(String path, String bearer) throws Exception {
        String body = mvc.perform(post(path + "/ticket").header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("ticket").asText();
    }
}
