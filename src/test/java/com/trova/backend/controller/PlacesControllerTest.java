package com.trova.backend.controller;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.service.ItineraryGenerationService;
import org.junit.jupiter.api.Test;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.doThrow;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlacesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private SavedPlaceRepository savedPlaceRepository;

    @Autowired
    private com.trova.backend.service.FoundPlaceNameStore foundPlaceNameStore;

    @MockitoBean
    private ItineraryGenerationService itineraryGenerationService;

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

    private org.springframework.test.web.servlet.request.RequestPostProcessor loginAs(String sub, String name) {
        return oauth2Login()
                .clientRegistration(googleRegistration())
                .attributes(attrs -> {
                    attrs.put("sub", sub);
                    attrs.put("name", name);
                    attrs.put("picture", "https://example.com/p.jpg");
                });
    }

    @Test
    void 본인_장소_목록만_조회된다() throws Exception {
        User me = userRepository.save(new User("google", "aaa", "나", null));
        User other = userRepository.save(new User("google", "bbb", "남", null));
        ProcessingJob myJob = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/x", SourcePlatform.YOUTUBE));
        ProcessingJob otherJob = processingJobRepository.save(new ProcessingJob(other, "https://youtu.be/y", SourcePlatform.YOUTUBE));
        savedPlaceRepository.save(new SavedPlace(myJob, me, "내 장소", "서울", "cafe", 37.5, 127.0));
        savedPlaceRepository.save(new SavedPlace(otherJob, other, "남의 장소", "부산", "cafe", 35.1, 129.0));

        mockMvc.perform(get("/api/places").with(loginAs("aaa", "나")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].placeName").value("내 장소"));
    }

    @Test
    void 타인_소유_장소_단건_조회는_404() throws Exception {
        User me = userRepository.save(new User("google", "ccc", "나2", null));
        User other = userRepository.save(new User("google", "ddd", "남2", null));
        ProcessingJob otherJob = processingJobRepository.save(new ProcessingJob(other, "https://youtu.be/z", SourcePlatform.YOUTUBE));
        SavedPlace otherPlace = savedPlaceRepository.save(new SavedPlace(otherJob, other, "남의 장소", null, "cafe", null, null));

        mockMvc.perform(get("/api/places/" + otherPlace.getId()).with(loginAs("ccc", "나2")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 본인_장소_삭제_성공() throws Exception {
        User me = userRepository.save(new User("google", "eee", "나3", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/w", SourcePlatform.YOUTUBE));
        SavedPlace place = savedPlaceRepository.save(new SavedPlace(job, me, "삭제될 장소", null, "cafe", null, null));

        mockMvc.perform(delete("/api/places/" + place.getId()).with(loginAs("eee", "나3")))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(savedPlaceRepository.findById(place.getId())).isEmpty();
    }

    @Test
    void pending_작업만_조회된다() throws Exception {
        User me = userRepository.save(new User("google", "fff", "나4", null));
        ProcessingJob pending = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/pending", SourcePlatform.YOUTUBE));
        ProcessingJob done = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/done", SourcePlatform.YOUTUBE));
        done.markProcessing();
        done.markDone();
        processingJobRepository.save(done);

        mockMvc.perform(get("/api/places/pending").with(loginAs("fff", "나4")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jobId").value(pending.getId()));
    }

    @Test
    void 실패한_작업도_pending_목록에_포함된다() throws Exception {
        User me = userRepository.save(new User("google", "ggg", "나5", null));
        ProcessingJob failed = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/failed", SourcePlatform.YOUTUBE));
        failed.markFailed("yt-dlp 403");
        processingJobRepository.save(failed);

        mockMvc.perform(get("/api/places/pending").with(loginAs("ggg", "나5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jobId").value(failed.getId()))
                .andExpect(jsonPath("$[0].status").value("FAILED"));
    }

    // #83: 실패 카드는 그 영상의 가장 최근 작업이 실패일 때만 보인다. 운영에서 9월 초 실패한 영상들이 나중에 다시 성공했는데도
    // 실패 카드가 영상 기록 맨 위에 계속 남았고, 30초 간격으로 두 번 실패한 영상은 카드가 두 장이었다.
    private ProcessingJob failedJob(User user, String url) {
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(user, url, SourcePlatform.YOUTUBE));
        job.markFailed("파이프라인 실행 실패(exit=1)");
        return processingJobRepository.save(job);
    }

    private ProcessingJob doneJob(User user, String url) {
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(user, url, SourcePlatform.YOUTUBE));
        job.markProcessing();
        job.markDone();
        return processingJobRepository.save(job);
    }

    @Test
    void 나중에_다시_성공한_영상의_실패는_목록에서_빠진다() throws Exception {
        User me = userRepository.save(new User("google", "sup1", "나", null));
        failedJob(me, "https://www.youtube.com/shorts/sameVideo1");
        doneJob(me, "https://www.youtube.com/shorts/sameVideo1");

        mockMvc.perform(get("/api/places/pending").with(loginAs("sup1", "나")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 같은_영상이_여러_번_실패하면_가장_최근_실패만_보인다() throws Exception {
        User me = userRepository.save(new User("google", "sup2", "나", null));
        failedJob(me, "https://www.youtube.com/shorts/twiceFail");
        ProcessingJob latest = failedJob(me, "https://www.youtube.com/shorts/twiceFail");

        mockMvc.perform(get("/api/places/pending").with(loginAs("sup2", "나")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jobId").value(latest.getId()));
    }

    @Test
    void 다시_시도해_처리_중이면_예전_실패는_빠지고_처리_중만_보인다() throws Exception {
        User me = userRepository.save(new User("google", "sup3", "나", null));
        failedJob(me, "https://www.youtube.com/shorts/retrying");
        ProcessingJob retry = processingJobRepository.save(
                new ProcessingJob(me, "https://www.youtube.com/shorts/retrying", SourcePlatform.YOUTUBE));

        mockMvc.perform(get("/api/places/pending").with(loginAs("sup3", "나")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jobId").value(retry.getId()));
    }

    @Test
    void 주소_형식이_달라도_같은_영상이면_같은_영상으로_본다() throws Exception {
        User me = userRepository.save(new User("google", "sup4", "나", null));
        // 정식 주소로 바꾸기(#49) 전에 저장된 예전 작업은 ?si= 같은 꼬리가 붙어 있다(운영 DB에 실제로 있음).
        failedJob(me, "https://youtube.com/shorts/formVideo?si=abc123");
        doneJob(me, "https://www.youtube.com/shorts/formVideo");

        mockMvc.perform(get("/api/places/pending").with(loginAs("sup4", "나")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 다른_영상의_성공은_실패_카드에_영향이_없다() throws Exception {
        User me = userRepository.save(new User("google", "sup5", "나", null));
        ProcessingJob failed = failedJob(me, "https://www.youtube.com/shorts/onlyFailed");
        doneJob(me, "https://www.youtube.com/shorts/otherVideo");

        mockMvc.perform(get("/api/places/pending").with(loginAs("sup5", "나")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jobId").value(failed.getId()));
    }

    @Test
    void 장소_목록에_영상_제목이_포함된다() throws Exception {
        User me = userRepository.save(new User("google", "jjj", "제목유저", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(me, "https://youtu.be/title2", SourcePlatform.YOUTUBE));
        job.setTitle("부산 여행 브이로그");
        processingJobRepository.save(job);
        savedPlaceRepository.save(new SavedPlace(job, me, "해운대", "부산", "attraction", 35.16, 129.16));

        mockMvc.perform(get("/api/places").with(loginAs("jjj", "제목유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("부산 여행 브이로그"));
    }

    @Test
    void pending_목록에_영상_제목이_포함된다() throws Exception {
        User me = userRepository.save(new User("google", "kkk", "제목유저2", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(me, "https://youtu.be/title3", SourcePlatform.YOUTUBE));
        job.setTitle("서울 카페 투어");
        processingJobRepository.save(job);

        mockMvc.perform(get("/api/places/pending").with(loginAs("kkk", "제목유저2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("서울 카페 투어"));
    }

    @Test
    void pending_목록에_분석_중_찾은_장소_이름이_포함된다() throws Exception {
        // 분석이 끝나기 전에 파이프라인이 알려준 이름을 앱이 폴링으로 받아 보여준다(#51).
        User me = userRepository.save(new User("google", "found1", "찾은이름유저", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(me, "https://youtu.be/found1", SourcePlatform.YOUTUBE));
        foundPlaceNameStore.put(job.getId(), List.of("해운대 암소갈비집", "흰여울 카페"));

        try {
            mockMvc.perform(get("/api/places/pending").with(loginAs("found1", "찾은이름유저")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].foundPlaceNames[0]").value("해운대 암소갈비집"))
                    .andExpect(jsonPath("$[0].foundPlaceNames[1]").value("흰여울 카페"));
        } finally {
            foundPlaceNameStore.clear(job.getId());
        }
    }

    @Test
    void 장소를_못_찾아_실패한_작업은_failureReason이_NO_PLACES다() throws Exception {
        // 앱이 "장소를 찾지 못했어요"를 일반 실패와 구분해서 보여줄 수 있게 한다(#55).
        User me = userRepository.save(new User("google", "noplace1", "빈결과유저", null));
        ProcessingJob noPlaces = processingJobRepository.save(
                new ProcessingJob(me, "https://youtu.be/noplace1", SourcePlatform.YOUTUBE));
        noPlaces.markFailed(ProcessingJob.NO_PLACES_MESSAGE);
        processingJobRepository.save(noPlaces);
        ProcessingJob otherFailure = processingJobRepository.save(
                new ProcessingJob(me, "https://youtu.be/noplace2", SourcePlatform.YOUTUBE));
        otherFailure.markFailed("파이프라인 실행 실패(exit=1)");
        processingJobRepository.save(otherFailure);

        mockMvc.perform(get("/api/places/pending").with(loginAs("noplace1", "빈결과유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.jobId == %d)].failureReason", noPlaces.getId()).value("NO_PLACES"))
                .andExpect(jsonPath("$[?(@.jobId == %d)].failureReason", otherFailure.getId()).value(org.hamcrest.Matchers.contains((Object) null)));
    }

    @Test
    void AI_한도로_실패한_작업은_failureReason이_AI_QUOTA다() throws Exception {
        User me = userRepository.save(new User("google", "quota1", "한도유저", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(me, "https://youtu.be/quota1", SourcePlatform.YOUTUBE));
        job.markFailed(ProcessingJob.AI_QUOTA_MESSAGE);
        processingJobRepository.save(job);

        mockMvc.perform(get("/api/places/pending").with(loginAs("quota1", "한도유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].failureReason").value("AI_QUOTA"));
    }

    @Test
    void 인스타그램_속도_제한으로_실패한_작업은_failureReason이_SOURCE_RATE_LIMITED다() throws Exception {
        User me = userRepository.save(new User("google", "ratelimit1", "제한유저", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(me, "https://www.instagram.com/reel/rl1/", SourcePlatform.INSTAGRAM));
        job.markFailed(ProcessingJob.SOURCE_RATE_LIMIT_MESSAGE);
        processingJobRepository.save(job);

        mockMvc.perform(get("/api/places/pending").with(loginAs("ratelimit1", "제한유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].failureReason").value("SOURCE_RATE_LIMITED"));
    }

    @Test
    void 일정형_장소는_dayNumber와_orderInDay를_반환한다() throws Exception {
        User me = userRepository.save(new User("google", "hhh", "일정유저", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(me, "https://youtu.be/itinerary2", SourcePlatform.YOUTUBE));
        savedPlaceRepository.save(
                new SavedPlace(job, me, "해운대", "부산", "attraction", 35.16, 129.16, 2, 3));

        mockMvc.perform(get("/api/places").with(loginAs("hhh", "일정유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dayNumber").value(2))
                .andExpect(jsonPath("$[0].orderInDay").value(3));
    }

    @Test
    void 일정형이_아닌_장소는_dayNumber가_null이다() throws Exception {
        User me = userRepository.save(new User("google", "iii", "일반유저2", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(me, "https://youtu.be/normal2", SourcePlatform.YOUTUBE));
        savedPlaceRepository.save(
                new SavedPlace(job, me, "장소", null, "cafe", null, null));

        mockMvc.perform(get("/api/places").with(loginAs("iii", "일반유저2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dayNumber").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void 장소를_다른_날로_옮기면_해당_날_맨_뒤에_배정된다() throws Exception {
        User me = userRepository.save(new User("google", "day1", "일정편집1", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/day1", SourcePlatform.YOUTUBE));
        savedPlaceRepository.save(new SavedPlace(job, me, "1일차 장소", "부산", "cafe", 35.1, 129.0, 1, 1));
        SavedPlace moving = savedPlaceRepository.save(new SavedPlace(job, me, "옮길 장소", "부산", "cafe", 35.2, 129.1, 2, 1));

        mockMvc.perform(patch("/api/places/" + moving.getId() + "/day")
                        .with(loginAs("day1", "일정편집1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayNumber\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dayNumber").value(1))
                .andExpect(jsonPath("$.orderInDay").value(2));
    }

    @Test
    void 타인_소유_장소를_다른_날로_옮기려_하면_404() throws Exception {
        User me = userRepository.save(new User("google", "day2", "일정편집2", null));
        User other = userRepository.save(new User("google", "day3", "일정편집3", null));
        ProcessingJob otherJob = processingJobRepository.save(new ProcessingJob(other, "https://youtu.be/day2", SourcePlatform.YOUTUBE));
        SavedPlace otherPlace = savedPlaceRepository.save(new SavedPlace(otherJob, other, "남의 장소", "부산", "cafe", 35.1, 129.0, 1, 1));

        mockMvc.perform(patch("/api/places/" + otherPlace.getId() + "/day")
                        .with(loginAs("day2", "일정편집2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayNumber\": 1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void dayNumber가_1보다_작으면_400() throws Exception {
        User me = userRepository.save(new User("google", "day4", "일정편집4", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/day4", SourcePlatform.YOUTUBE));
        SavedPlace place = savedPlaceRepository.save(new SavedPlace(job, me, "장소", "부산", "cafe", 35.1, 129.0, 1, 1));

        mockMvc.perform(patch("/api/places/" + place.getId() + "/day")
                        .with(loginAs("day4", "일정편집4"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayNumber\": 0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 같은_날_안에서_아래로_순서를_바꾸면_인접한_장소와_교체된다() throws Exception {
        User me = userRepository.save(new User("google", "order1", "순서1", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/order1", SourcePlatform.YOUTUBE));
        SavedPlace first = savedPlaceRepository.save(new SavedPlace(job, me, "첫번째", "부산", "cafe", 35.1, 129.0, 1, 1));
        SavedPlace second = savedPlaceRepository.save(new SavedPlace(job, me, "두번째", "부산", "cafe", 35.2, 129.1, 1, 2));

        mockMvc.perform(patch("/api/places/" + first.getId() + "/order")
                        .with(loginAs("order1", "순서1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\": \"DOWN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderInDay").value(2));

        org.assertj.core.api.Assertions.assertThat(
                        savedPlaceRepository.findById(second.getId()).orElseThrow().getOrderInDay())
                .isEqualTo(1);
    }

    @Test
    void 맨_위에서_위로_순서를_바꾸면_아무_변화_없다() throws Exception {
        User me = userRepository.save(new User("google", "order2", "순서2", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/order2", SourcePlatform.YOUTUBE));
        SavedPlace first = savedPlaceRepository.save(new SavedPlace(job, me, "첫번째", "부산", "cafe", 35.1, 129.0, 1, 1));

        mockMvc.perform(patch("/api/places/" + first.getId() + "/order")
                        .with(loginAs("order2", "순서2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\": \"UP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderInDay").value(1));
    }

    @Test
    void direction이_UP_DOWN이_아니면_400() throws Exception {
        User me = userRepository.save(new User("google", "order3", "순서3", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/order3", SourcePlatform.YOUTUBE));
        SavedPlace place = savedPlaceRepository.save(new SavedPlace(job, me, "장소", "부산", "cafe", 35.1, 129.0, 1, 1));

        mockMvc.perform(patch("/api/places/" + place.getId() + "/order")
                        .with(loginAs("order3", "순서3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\": \"SIDEWAYS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void direction이_없으면_400() throws Exception {
        User me = userRepository.save(new User("google", "order4", "순서4", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/order4", SourcePlatform.YOUTUBE));
        SavedPlace place = savedPlaceRepository.save(new SavedPlace(job, me, "장소", "부산", "cafe", 35.1, 129.0, 1, 1));

        mockMvc.perform(patch("/api/places/" + place.getId() + "/order")
                        .with(loginAs("order4", "순서4"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 일정_생성_대기열이_가득_차면_503과_안내_문구를_준다() throws Exception {
        User me = userRepository.save(new User("google", "gen-full", "생성꽉참", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/gen-full", SourcePlatform.YOUTUBE));
        job.markDone();
        processingJobRepository.save(job);
        doThrow(new TaskRejectedException("queue full")).when(itineraryGenerationService).generate(anyLong());

        mockMvc.perform(post("/api/places/videos/" + job.getId() + "/itinerary")
                        .with(loginAs("gen-full", "생성꽉참")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("지금 처리 요청이 많아요. 잠시 후 다시 시도해주세요."));
    }

    @Test
    void 일정_생성_요청은_202를_반환하고_비동기_서비스를_호출한다() throws Exception {
        User me = userRepository.save(new User("google", "gen1", "생성1", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/gen1", SourcePlatform.YOUTUBE));
        job.markDone();
        processingJobRepository.save(job);
        savedPlaceRepository.save(new SavedPlace(job, me, "장소", "부산", "cafe", 35.1, 129.0));
        doNothing().when(itineraryGenerationService).generate(anyLong());

        mockMvc.perform(post("/api/places/videos/" + job.getId() + "/itinerary")
                        .with(loginAs("gen1", "생성1")))
                .andExpect(status().isAccepted());

        verify(itineraryGenerationService).generate(job.getId());
    }

    @Test
    void 타인_소유_영상의_일정_생성_요청은_404() throws Exception {
        User me = userRepository.save(new User("google", "gen2", "생성2", null));
        User other = userRepository.save(new User("google", "gen3", "생성3", null));
        ProcessingJob otherJob = processingJobRepository.save(new ProcessingJob(other, "https://youtu.be/gen2", SourcePlatform.YOUTUBE));

        mockMvc.perform(post("/api/places/videos/" + otherJob.getId() + "/itinerary")
                        .with(loginAs("gen2", "생성2")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 장소_목록_응답에_jobId가_포함된다() throws Exception {
        User me = userRepository.save(new User("google", "jobid1", "jobId유저", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/jobid1", SourcePlatform.YOUTUBE));
        savedPlaceRepository.save(new SavedPlace(job, me, "장소", "부산", "cafe", 35.1, 129.0));

        mockMvc.perform(get("/api/places").with(loginAs("jobid1", "jobId유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").value(job.getId()));
    }
}
