package com.trova.backend.controller;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 홈 카드용 자동 일정 초안 목록·닫기 API(#136). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TripDraftAutoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private TripDraftRepository tripDraftRepository;

    @Autowired
    private SavedPlaceRepository savedPlaceRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private ItineraryRepository itineraryRepository;

    @Autowired
    private TripPlaceRepository tripPlaceRepository;

    private ClientRegistration googleRegistration() {
        return ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .build();
    }

    private RequestPostProcessor loginAs(String sub, String name) {
        return oauth2Login()
                .clientRegistration(googleRegistration())
                .attributes(attrs -> {
                    attrs.put("sub", sub);
                    attrs.put("name", name);
                    attrs.put("picture", "https://example.com/p.jpg");
                });
    }

    private ProcessingJob videoJob(User user, String url, String title) {
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(user, url, SourcePlatform.YOUTUBE));
        job.setTitle(title);
        return processingJobRepository.save(job);
    }

    /** createdAt이 같은 밀리초에 찍혀 순서가 뒤섞이지 않게 아주 짧게 띈다. */
    private void tick() throws InterruptedException {
        Thread.sleep(5);
    }

    @Test
    void 자동초안_목록_조회는_닫지않은_내_자동초안만_최신순으로_준다() throws Exception {
        User me = userRepository.save(new User("google", "auto1", "자동유저1", null));
        User other = userRepository.save(new User("google", "auto1-other", "남", null));

        ProcessingJob job1 = videoJob(me, "https://youtu.be/Auto001", "제주 여행 영상");
        ProcessingJob job2 = videoJob(me, "https://youtu.be/Auto002", "부산 여행 영상");

        // 1) 내 자동 초안 READY
        TripDraft ready = TripDraft.auto(me, List.of(job1.getId()), "제주 영상 공유");
        ready.applyRequest(2, LocalDate.of(2026, 3, 1), "CODE");
        ready.markReady("{}", "{}");
        tripDraftRepository.save(ready);
        tick();

        // 2) 내 자동 초안 PROCESSING (가장 최신)
        TripDraft processing = TripDraft.auto(me, List.of(job2.getId()), "부산 영상 공유");
        processing.markProcessing();
        tripDraftRepository.save(processing);
        tick();

        // 3) 내 자동 초안인데 닫은 것 — 목록에서 빠져야 함
        TripDraft dismissed = TripDraft.auto(me, List.of(job1.getId()), "닫은 초안");
        dismissed.markReady("{}", "{}");
        dismissed.dismiss();
        tripDraftRepository.save(dismissed);
        tick();

        // 4) 수동으로 만든 초안(autoCreated=false) — 목록에서 빠져야 함
        TripDraft manual = new TripDraft(me, List.of(job1.getId()), "수동 초안");
        manual.markReady("{}", "{}");
        tripDraftRepository.save(manual);
        tick();

        // 5) 남의 자동 초안 — 목록에서 빠져야 함
        ProcessingJob otherJob = videoJob(other, "https://youtu.be/Auto003", "남의 영상");
        TripDraft othersDraft = TripDraft.auto(other, List.of(otherJob.getId()), "남의 초안");
        othersDraft.markReady("{}", "{}");
        tripDraftRepository.save(othersDraft);

        mockMvc.perform(get("/api/trip-drafts/auto").with(loginAs("auto1", "자동유저1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].draftId").value(processing.getId()))
                .andExpect(jsonPath("$[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$[0].jobId").value(job2.getId()))
                .andExpect(jsonPath("$[0].videoTitle").value("부산 여행 영상"))
                .andExpect(jsonPath("$[1].draftId").value(ready.getId()))
                .andExpect(jsonPath("$[1].status").value("READY"))
                .andExpect(jsonPath("$[1].jobId").value(job1.getId()))
                .andExpect(jsonPath("$[1].videoTitle").value("제주 여행 영상"))
                .andExpect(jsonPath("$[1].days").value(2));
    }

    @Test
    void 영상이_이미_여행이_됐으면_READY_자동초안이어도_목록에서_빠진다() throws Exception {
        User me = userRepository.save(new User("google", "auto5", "자동유저5", null));
        ProcessingJob job = videoJob(me, "https://youtu.be/Auto020", "오사카 여행 영상");
        SavedPlace place = savedPlaceRepository.save(new SavedPlace(job, me, "도톤보리", "오사카", "attraction", 34.66, 135.5));

        TripDraft ready = TripDraft.auto(me, List.of(job.getId()), "오사카 영상 공유");
        ready.applyRequest(2, LocalDate.of(2026, 4, 1), "CODE");
        ready.markReady("{}", "{}");
        tripDraftRepository.save(ready);

        Trip trip = tripRepository.save(new Trip(me, "오사카 여행", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 2)));
        Itinerary itinerary = itineraryRepository.save(new Itinerary(trip, 1, LocalDate.of(2026, 4, 1)));
        tripPlaceRepository.save(new TripPlace(itinerary, place.getPlaceName(), place.getRegion(), place.getCategory(),
                place.getLatitude(), place.getLongitude(), null, null, 1, PlaceSource.VIDEO, place.getId()));

        mockMvc.perform(get("/api/trip-drafts/auto").with(loginAs("auto5", "자동유저5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 자동초안_닫기에_성공하면_204이고_목록에서_빠진다() throws Exception {
        User me = userRepository.save(new User("google", "auto2", "자동유저2", null));
        ProcessingJob job = videoJob(me, "https://youtu.be/Auto010", "영상");
        TripDraft draft = TripDraft.auto(me, List.of(job.getId()), "메시지");
        draft.markReady("{}", "{}");
        tripDraftRepository.save(draft);

        mockMvc.perform(post("/api/trip-drafts/" + draft.getId() + "/dismiss").with(loginAs("auto2", "자동유저2")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/trip-drafts/auto").with(loginAs("auto2", "자동유저2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 남의_자동초안_닫기는_404() throws Exception {
        User owner = userRepository.save(new User("google", "auto3-owner", "주인", null));
        userRepository.save(new User("google", "auto3-other", "남", null));
        ProcessingJob job = videoJob(owner, "https://youtu.be/Auto011", "영상");
        TripDraft draft = TripDraft.auto(owner, List.of(job.getId()), "메시지");
        draft.markReady("{}", "{}");
        tripDraftRepository.save(draft);

        mockMvc.perform(post("/api/trip-drafts/" + draft.getId() + "/dismiss").with(loginAs("auto3-other", "남")))
                .andExpect(status().isNotFound());
    }

    @Test
    void APPROVED_초안_닫기는_409() throws Exception {
        User me = userRepository.save(new User("google", "auto4", "자동유저4", null));
        ProcessingJob job = videoJob(me, "https://youtu.be/Auto012", "영상");
        TripDraft draft = TripDraft.auto(me, List.of(job.getId()), "메시지");
        draft.markReady("{}", "{}");
        draft.markApproved(999L);
        tripDraftRepository.save(draft);

        mockMvc.perform(post("/api/trip-drafts/" + draft.getId() + "/dismiss").with(loginAs("auto4", "자동유저4")))
                .andExpect(status().isConflict());
    }
}
