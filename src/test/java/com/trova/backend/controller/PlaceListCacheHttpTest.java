package com.trova.backend.controller;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PlaceListCacheHttpTest {
    private static final AtomicLong clock = new AtomicLong();

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private ProcessingJobRepository jobs;
    @Autowired private JsonMapper json;
    @Autowired private TransactionTemplate transactions;
    @MockitoSpyBean private SavedPlaceRepository places;
    @TestBean private PlaceListCache listCache;

    static PlaceListCache listCache() {
        return new PlaceListCache(clock::get);
    }

    private byte[] list(String token) throws Exception {
        return mvc.perform(get("/api/places").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    @Test
    void 커밋된_저장_수정_삭제만_해당_사용자_목록에_즉시_반영한다() throws Exception {
        User owner = users.save(new User("google", "cache-owner", "owner", null));
        User other = users.save(new User("google", "cache-other", "other", null));
        String token = jwt.issue(owner);
        String otherToken = jwt.issue(other);
        ProcessingJob job = jobs.save(new ProcessingJob(owner, "test-video", SourcePlatform.YOUTUBE));
        assertThat(list(token)).asString().isEqualTo("[]");
        assertThat(list(otherToken)).asString().isEqualTo("[]");

        SavedPlace first = new SavedPlace(job, owner, "서울 🍜 \"가게\"", null, "cafe", null, null);
        first.applyVideoNotes(List.of("줄 하나", "두 번째 줄"));
        places.save(first);
        places.save(new SavedPlace(job, owner, "둘", "서울", null, 37.5, 127.0));
        byte[] expected = json.writeValueAsBytes(VideoResults.latestRows(places.findListRowsByUserId(owner.getId()))
                .stream().map(PlacesController.PlaceResponse::from).toList());
        clearInvocations(places);
        assertThat(list(token)).containsExactly(expected);
        assertThat(list(token)).containsExactly(expected);
        assertThat(list(otherToken)).asString().isEqualTo("[]");
        verify(places).findListRowsByUserId(owner.getId());
        verify(places, times(0)).findListRowsByUserId(other.getId());

        transactions.executeWithoutResult(status -> places.findById(first.getId()).orElseThrow().assignToDay(3, 1));
        byte[] changed = list(token);
        assertThat(json.readTree(changed).get(1).get("dayNumber").asInt()).isEqualTo(3);
        transactions.executeWithoutResult(status -> {
            places.deleteById(first.getId());
            places.flush();
            status.setRollbackOnly();
        });
        assertThat(list(token)).containsExactly(changed);
        verify(places, times(2)).findListRowsByUserId(owner.getId());

        jdbc.update("update saved_places set region = ? where id = ?", "부산", first.getId());
        assertThat(list(token)).containsExactly(changed);
        clock.addAndGet(PlaceListCache.TTL_NANOS);
        assertThat(json.readTree(list(token)).get(1).get("region").asString()).isEqualTo("부산");

        mvc.perform(delete("/api/places/" + first.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(json.readTree(list(token)).size()).isEqualTo(1);
        mvc.perform(get("/api/places")).andExpect(status().isUnauthorized());
    }
}
