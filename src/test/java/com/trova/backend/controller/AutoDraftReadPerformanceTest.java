package com.trova.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.planner.DraftGenerator;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.service.TripPlannerService;
import com.trova.backend.service.TripService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 테스트 트랜잭션 없이 실제 HTTP 요청의 SQL과 엔티티 로딩을 센다.
// P2 성능 테스트의 격리 컨텍스트를 공유해 기본 testdb의 create-drop 수명에 간섭하지 않는다.
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:p2-query-count;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.jpa.properties.hibernate.generate_statistics=true",
        "app.admin.users=google:p2-admin"})
class AutoDraftReadPerformanceTest {
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private ProcessingJobRepository jobs;
    @Autowired private TripDraftRepository drafts;
    @Autowired private EntityManagerFactory emf;
    @Autowired private SavedPlaceRepository savedPlaces;
    @Autowired private TripRepository trips;
    @Autowired private ItineraryRepository itineraries;
    @Autowired private TripPlaceRepository tripPlaces;
    @Autowired private TripPlannerService planner;
    @Autowired private TripService tripService;

    // 변경 전 컨트롤러 경로로 전체 DTO를 만들어 새 HTTP 응답과 대조한다.
    private List<TripDraftController.AutoDraftResponse> legacy(User user) {
        List<TripDraft> listed = drafts.findListedByUserId(user.getId(), List.of(
                TripDraftStatus.PENDING, TripDraftStatus.PROCESSING, TripDraftStatus.READY));
        if (listed.isEmpty()) return List.of();
        TripPlannerService.Analyses analyses = planner.analysesOf(user.getId());
        Set<Long> tripped = tripService.jobIdsWithExistingTrip(user.getId(), listed.stream()
                .map(d -> analyses.byId().get(d.getJobIds().getFirst())).filter(Objects::nonNull).distinct().toList());
        List<TripDraftController.AutoDraftResponse> result = new ArrayList<>();
        for (TripDraft draft : listed) {
            Long id = draft.getJobIds().getFirst();
            ProcessingJob original = analyses.byId().get(id);
            if (original != null && tripped.contains(original.getId())) continue;
            ProcessingJob shown = original == null ? null : analyses.latest(original);
            Integer count = null;
            if (draft.getStatus() == TripDraftStatus.READY && draft.getDraftJson() != null) {
                try {
                    count = DraftGenerator.fromJson(draft.getDraftJson()).days().stream()
                            .mapToInt(day -> day.items().size()).sum();
                } catch (RuntimeException ignored) {
                    count = null;
                }
            }
            result.add(new TripDraftController.AutoDraftResponse(draft.getId(), draft.getStatus().name(),
                    shown == null ? id : shown.getId(), shown == null ? null : shown.getTitle(),
                    draft.getDays(), draft.getCreatedAt().toString(), count));
            if (result.size() == 10) break;
        }
        return result;
    }

    private User user() {
        return users.save(new User("google", "p3-" + UUID.randomUUID(), "사용자", null));
    }

    private ProcessingJob job(User user, String url) {
        ProcessingJob job = new ProcessingJob(user, url, SourcePlatform.YOUTUBE);
        job.setTitle("영상");
        job.markDone();
        return jobs.save(job);
    }

    private String read(String bearer) throws Exception {
        return mvc.perform(get("/api/trip-drafts/auto").header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void 초안과_분석이_늘어도_SQL_세번이고_엔티티를_읽지_않는다() throws Exception {
        List<Long> sql = new ArrayList<>();
        List<Long> loaded = new ArrayList<>();
        for (int count : new int[] {1, 8, 20}) {
            User user = user();
            for (int i = 0; i < count; i++) {
                ProcessingJob job = job(user, "https://youtu.be/" + UUID.randomUUID());
                drafts.save(TripDraft.auto(user, List.of(job.getId()), "당일치기"));
            }
            String bearer = "Bearer " + jwt.issue(user);
            String expected = read(bearer);
            ObjectMapper mapper = new ObjectMapper();
            assertThat(mapper.readTree(expected)).isEqualTo(mapper.readTree(mapper.writeValueAsString(legacy(user))));
            Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
            stats.clear();
            assertThat(read(bearer)).isEqualTo(expected);
            sql.add(stats.getPrepareStatementCount());
            loaded.add(stats.getEntityLoadCount());
            mvc.perform(get("/api/trip-drafts/auto").header("Authorization", bearer))
                    .andExpect(jsonPath("$.length()").value(Math.min(count, 10)));
        }
        System.out.println("P3 drafts=1,8,20 SQL=" + sql + " entities=" + loaded);
        assertThat(sql).containsExactly(3L, 3L, 3L);
        assertThat(loaded).containsOnly(0L);
    }

    @Test
    void 빈_목록은_SQL_한번이고_쓰기_직후의_변경을_다음_조회에서_본다() throws Exception {
        User user = user();
        String bearer = "Bearer " + jwt.issue(user);
        read(bearer);
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        assertThat(read(bearer)).isEqualTo("[]");
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
        ProcessingJob first = job(user, "https://youtu.be/p3-refresh");
        TripDraft draft = drafts.save(TripDraft.auto(user, List.of(first.getId()), "당일치기"));
        mvc.perform(get("/api/trip-drafts/auto").header("Authorization", bearer))
                .andExpect(jsonPath("$[0].jobId").value(first.getId()));
        ProcessingJob latest = job(user, "https://www.youtube.com/shorts/p3-refresh");
        mvc.perform(get("/api/trip-drafts/auto").header("Authorization", bearer))
                .andExpect(jsonPath("$[0].jobId").value(latest.getId()));
        draft.dismiss();
        drafts.save(draft);
        assertThat(read(bearer)).isEqualTo("[]");
    }

    @Test
    void 여행으로_숨긴_뒤_열개를_고르고_장소와_여행_삭제를_바로_반영한다() throws Exception {
        User user = user();
        String bearer = "Bearer " + jwt.issue(user);
        for (int i = 0; i < 12; i++) {
            ProcessingJob job = job(user, "https://youtu.be/visible-" + i);
            TripDraft draft = TripDraft.auto(user, List.of(job.getId()), "당일치기");
            draft.markReady("{}", i % 2 == 0 ? "{}" :
                    "{\"days\":[{\"day\":1,\"items\":[{\"name\":\"카페\"}]}]}");
            drafts.save(draft);
        }
        ProcessingJob hidden = job(user, "https://youtu.be/hidden");
        TripDraft hiddenDraft = drafts.save(TripDraft.auto(user, List.of(hidden.getId()), "숨김"));
        ProcessingJob alias = job(user, "https://www.youtube.com/shorts/hidden?si=x");
        SavedPlace saved = savedPlaces.save(new SavedPlace(alias, user, "카페", "서울", "cafe", 37.5, 127.0));
        Trip trip = trips.save(new Trip(user, "여행", null, null));
        Itinerary day = itineraries.save(new Itinerary(trip, 1, null));
        tripPlaces.save(new TripPlace(day, "카페", "서울", "cafe", 37.5, 127.0,
                null, null, 0, PlaceSource.VIDEO, saved.getId()));
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(read(bearer))).isEqualTo(mapper.readTree(mapper.writeValueAsString(legacy(user))));
        mvc.perform(get("/api/trip-drafts/auto").header("Authorization", bearer))
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[0].draftId").value(org.hamcrest.Matchers.not(hiddenDraft.getId().intValue())));
        savedPlaces.delete(saved);
        mvc.perform(get("/api/trip-drafts/auto").header("Authorization", bearer))
                .andExpect(jsonPath("$[0].draftId").value(hiddenDraft.getId()));
        saved = savedPlaces.save(new SavedPlace(alias, user, "카페", "서울", "cafe", 37.5, 127.0));
        tripPlaces.save(new TripPlace(day, "카페", "서울", "cafe", 37.5, 127.0,
                null, null, 1, PlaceSource.VIDEO, saved.getId()));
        assertThat(mapper.readTree(read(bearer))).isEqualTo(mapper.readTree(mapper.writeValueAsString(legacy(user))));
        tripService.deleteTrip(user, trip.getId());
        mvc.perform(get("/api/trip-drafts/auto").header("Authorization", bearer))
                .andExpect(jsonPath("$[0].draftId").value(hiddenDraft.getId()));
    }

    @Test
    void 다시_짠_초안과_없는_영상과_실패한_최신_분석도_기존_응답과_같다() throws Exception {
        User user = user();
        User other = user();
        ProcessingJob first = job(user, "https://youtu.be/p3-latest");
        ProcessingJob done = job(user, "https://www.youtube.com/watch?v=p3-latest");
        done.setTitle("최근 완료 영상");
        jobs.save(done);
        ProcessingJob failed = job(user, "https://www.youtube.com/shorts/p3-latest");
        failed.markFailed("실패");
        jobs.save(failed);
        job(other, "https://youtu.be/p3-latest");
        TripDraft replacement = new TripDraft(user, List.of(first.getId(), done.getId()), "다시 짜기");
        replacement.replaces(123L);
        replacement.applyRequest(2, null, "CODE");
        replacement.markReady("{}", "{\"days\":[{\"day\":1,\"items\":[{\"name\":\"카페\"}]}]}");
        drafts.save(replacement);
        TripDraft missing = TripDraft.auto(user, List.of(-100L), "없는 영상");
        missing.markReady("{}", "broken json");
        drafts.save(missing);
        drafts.save(new TripDraft(user, List.of(first.getId()), "직접 만든 초안"));
        drafts.save(TripDraft.auto(other, List.of(first.getId()), "다른 사용자"));
        String bearer = "Bearer " + jwt.issue(user);
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(read(bearer))).isEqualTo(mapper.readTree(mapper.writeValueAsString(legacy(user))));
        mvc.perform(get("/api/trip-drafts/auto").header("Authorization", bearer))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].jobId").value(-100))
                .andExpect(jsonPath("$[0].videoTitle").isEmpty())
                .andExpect(jsonPath("$[0].placeCount").isEmpty())
                .andExpect(jsonPath("$[1].jobId").value(done.getId()))
                .andExpect(jsonPath("$[1].placeCount").value(1));
    }
}
