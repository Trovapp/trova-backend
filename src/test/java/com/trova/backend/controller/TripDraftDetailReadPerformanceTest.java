package com.trova.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.service.UserAccountService;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 초안 상세(P6, #473)의 응답·SQL·엔티티 로딩을 테스트 트랜잭션 없이 실제 HTTP 요청으로 잰다.
// P2/P3 성능 테스트의 격리 컨텍스트를 공유해 기본 testdb의 create-drop 수명에 간섭하지 않는다.
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:p2-query-count;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.jpa.properties.hibernate.generate_statistics=true",
        "app.admin.users=google:p2-admin"})
class TripDraftDetailReadPerformanceTest {
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private ProcessingJobRepository jobs;
    @Autowired private TripDraftRepository drafts;
    @Autowired private SavedPlaceRepository savedPlaces;
    @Autowired private EntityManagerFactory emf;
    @Autowired private UserAccountService userAccountService;

    private final ObjectMapper mapper = new ObjectMapper();

    // ---- 변경 전 경로(엔티티로 사용자의 저장 장소 전체를 읽던 currentJobIds)를 그대로 옮긴 기준값 ----

    private static final List<Pattern> RULES = List.of(
            Pattern.compile("youtube(?:-nocookie)?\\.com/shorts/([\\w-]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("youtube(?:-nocookie)?\\.com/watch\\?(?:.*&)?v=([\\w-]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("youtu\\.be/([\\w-]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("instagram\\.com/(?:reels?|tv)/([\\w-]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("instagram\\.com/p/([\\w-]+)", Pattern.CASE_INSENSITIVE));
    private static final List<String> PLATFORMS = List.of("yt", "yt", "yt", "ig", "ig");

    // VideoKey는 패키지 전용이라 같은 규칙을 복사했다(규칙이 바뀌면 이 기준값도 함께 바뀌어야 한다).
    private static String videoKey(String url) {
        if (url == null) return "";
        for (int i = 0; i < RULES.size(); i++) {
            Matcher matcher = RULES.get(i).matcher(url);
            if (matcher.find()) return PLATFORMS.get(i) + ":" + matcher.group(1);
        }
        return url.trim();
    }

    private List<Long> legacyCurrentJobIds(TripDraft draft) {
        List<Long> jobIds = draft.getJobIds();
        if (draft.getTripId() != null
                || (draft.getStatus() != TripDraftStatus.READY && draft.getStatus() != TripDraftStatus.NEEDS_INPUT)) {
            return jobIds;
        }
        Map<Long, String> videoByJob = new HashMap<>();
        jobs.findAllById(jobIds).forEach(job -> videoByJob.put(job.getId(), videoKey(job.getSourceUrl())));
        Map<String, Long> latestByVideo = new HashMap<>();
        for (SavedPlace place : savedPlaces.findByUserOrderByCreatedAtDescIdDesc(draft.getUser())) {
            ProcessingJob job = place.getProcessingJob();
            latestByVideo.merge(videoKey(job.getSourceUrl()), job.getId(), Math::max);
        }
        return jobIds.stream()
                .map(id -> latestByVideo.getOrDefault(videoByJob.get(id), id))
                .distinct()
                .toList();
    }

    private String legacyJson(Long draftId) throws Exception {
        TripDraft draft = drafts.findById(draftId).orElseThrow();
        return mapper.writeValueAsString(TripDraftController.DraftResponse.from(draft, legacyCurrentJobIds(draft)));
    }

    // ---- 픽스처 ----

    private User user() {
        return users.save(new User("google", "p6-" + UUID.randomUUID(), "사용자", null));
    }

    private String bearer(User user) {
        return "Bearer " + jwt.issue(user);
    }

    private ProcessingJob job(User user, String url) {
        ProcessingJob job = new ProcessingJob(user, url, SourcePlatform.YOUTUBE);
        job.setTitle("영상");
        job.markDone();
        return jobs.save(job);
    }

    private SavedPlace place(ProcessingJob job, User user, String name) {
        return savedPlaces.save(new SavedPlace(job, user, name, "서울", "cafe", 37.5, 127.0));
    }

    /** 초안 영상 하나 + 다른 영상들의 저장 장소를 합쳐 사용자의 저장 장소가 savedCount개가 되게 한다. */
    private TripDraft readyDraftWithSaved(User user, int savedCount) {
        ProcessingJob original = job(user, "https://youtu.be/" + UUID.randomUUID());
        if (savedCount > 0) place(original, user, "초안 장소");
        for (int i = 1; i < savedCount; i++) {
            ProcessingJob other = job(user, "https://youtu.be/" + UUID.randomUUID());
            place(other, user, "다른 장소" + i);
        }
        TripDraft draft = TripDraft.auto(user, List.of(original.getId()), "당일치기");
        draft.markReady("{\"places\":[]}", "{\"days\":[]}");
        return drafts.save(draft);
    }

    private String read(String bearer, Long draftId) throws Exception {
        return mvc.perform(get("/api/trip-drafts/" + draftId).header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void assertSameAsLegacy(String bearer, Long draftId) throws Exception {
        assertThat(mapper.readTree(read(bearer, draftId))).isEqualTo(mapper.readTree(legacyJson(draftId)));
    }

    private List<Long> currentJobIdsOf(String bearer, Long draftId) throws Exception {
        long[] ids = mapper.readTree(read(bearer, draftId)).get("currentJobIds").traverse(mapper)
                .readValueAs(long[].class);
        return Arrays.stream(ids).boxed().toList();
    }

    // ---- 테스트 ----

    @Test
    void 저장_장소가_늘어도_READY_초안_상세는_SQL_세번이고_엔티티는_초안과_사용자뿐이다() throws Exception {
        List<Long> sql = new ArrayList<>();
        List<Long> loaded = new ArrayList<>();
        List<String> medians = new ArrayList<>();
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        for (int count : new int[] {1, 20, 100}) {
            User user = user();
            TripDraft draft = readyDraftWithSaved(user, count);
            String bearer = bearer(user);
            assertSameAsLegacy(bearer, draft.getId()); // 첫 요청: 사용자 확인 캐시를 데운다
            stats.clear();
            read(bearer, draft.getId());
            sql.add(stats.getPrepareStatementCount());
            loaded.add(stats.getEntityLoadCount());
            medians.add(String.format("%.3f", medianMillis(bearer, draft.getId())));
        }
        System.out.println("P6 saved=1,20,100 SQL=" + sql + " entities=" + loaded + " medianMs=" + medians);
        assertThat(sql).containsExactly(3L, 3L, 3L);
        assertThat(loaded).containsOnly(2L);
    }

    private double medianMillis(String bearer, Long draftId) throws Exception {
        for (int i = 0; i < 10; i++) read(bearer, draftId);
        double[] samples = new double[30];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            read(bearer, draftId);
            samples[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        Arrays.sort(samples);
        return (samples[14] + samples[15]) / 2;
    }

    @Test
    void 만드는_중이거나_실패했거나_여행이_된_초안은_최신_번호_조회를_하지_않는다() throws Exception {
        User user = user();
        String bearer = bearer(user);
        ProcessingJob original = job(user, "https://youtu.be/p6-state");
        place(job(user, "https://www.youtube.com/shorts/p6-state"), user, "재분석 장소");

        TripDraft pending = drafts.save(TripDraft.auto(user, List.of(original.getId()), "당일치기"));
        TripDraft processing = TripDraft.auto(user, List.of(original.getId()), "당일치기");
        processing.markProcessing();
        processing = drafts.save(processing);
        TripDraft failed = TripDraft.auto(user, List.of(original.getId()), "당일치기");
        failed.markFailed("Gemini 한도");
        failed = drafts.save(failed);
        TripDraft approved = TripDraft.auto(user, List.of(original.getId()), "당일치기");
        approved.markReady("{}", "{}");
        approved.markApproved(77L);
        approved = drafts.save(approved);

        read(bearer, pending.getId()); // 사용자 확인 캐시를 데운다
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        for (TripDraft draft : List.of(pending, processing, failed, approved)) {
            assertSameAsLegacy(bearer, draft.getId());
            stats.clear();
            read(bearer, draft.getId());
            assertThat(stats.getPrepareStatementCount()).as(draft.getStatus().name()).isEqualTo(1L);
            assertThat(currentJobIdsOf(bearer, draft.getId())).containsExactly(original.getId());
        }
    }

    @Test
    void 상태별_저장_장소_수별_응답이_기존과_같다() throws Exception {
        for (int count : new int[] {0, 1, 20, 100}) {
            User user = user();
            String bearer = bearer(user);
            TripDraft ready = readyDraftWithSaved(user, count);
            assertSameAsLegacy(bearer, ready.getId());

            Long originalId = ready.getJobIds().getFirst();
            TripDraft needsInput = TripDraft.auto(user, List.of(originalId), "당일치기");
            needsInput.markNeedsInput("두 지역이 멀어요", "{\"regions\":[\"서울\",\"부산\"]}");
            assertSameAsLegacy(bearer, drafts.save(needsInput).getId());

            TripDraft manual = new TripDraft(user, List.of(originalId), "2026-12-01 1박 2일");
            manual.applyRequest(2, java.time.LocalDate.of(2026, 12, 1), "GEMINI");
            manual.addGeminiCalls(3);
            manual.markReady("{}", "{\"days\":[{\"day\":1,\"items\":[{\"name\":\"카페\"}]}]}");
            manual.markPlacesChanged();
            assertSameAsLegacy(bearer, drafts.save(manual).getId());
        }
    }

    @Test
    void 여러_영상이_섞인_초안의_현재_번호는_장소가_남은_최대_작업이고_상태를_보지_않는다() throws Exception {
        User user = user();
        User other = user();
        String bearer = bearer(user);

        // 영상 A: 다시 분석한 작업(별칭 주소)에 장소가 있다 → 그 번호.
        ProcessingJob a1 = job(user, "https://youtu.be/p6-A");
        ProcessingJob a2 = job(user, "https://www.youtube.com/shorts/p6-A?si=x");
        place(a1, user, "A 옛 장소");
        place(a2, user, "A 새 장소");
        place(a2, user, "A 새 장소2");
        // 영상 B: 더 최근 작업은 실패(FAILED) 상태지만 장소가 남아 있다 → 상태 필터가 없으므로 그 번호.
        ProcessingJob b1 = job(user, "https://www.youtube.com/watch?v=p6-B");
        ProcessingJob b2 = job(user, "https://youtu.be/p6-B");
        b2.markFailed("다시 분석 실패");
        jobs.save(b2);
        place(b2, user, "B 실패 작업 장소");
        // 영상 C: 더 최근 작업의 장소가 모두 지워졌다 → 장소가 남은 옛 작업.
        ProcessingJob c1 = job(user, "https://instagram.com/reel/p6-C");
        place(c1, user, "C 장소");
        ProcessingJob c2 = job(user, "https://www.instagram.com/reels/p6-C/");
        SavedPlace deleted = place(c2, user, "C 지울 장소");
        savedPlaces.delete(deleted);
        // 영상 D: 어떤 작업에도 장소가 없다 → 원래 번호 그대로.
        ProcessingJob d1 = job(user, "https://youtu.be/p6-D");
        // 영상 E: 다른 사용자의 장소만 더 최근 작업에 있다 → 무시하고 원래 번호.
        ProcessingJob e1 = job(user, "https://youtu.be/p6-E");
        place(job(other, "https://youtu.be/p6-E"), other, "남의 장소");
        // 영상 A를 가리키는 두 번째 원래 작업 → 같은 현재 번호로 합쳐진다(중복 제거).

        TripDraft draft = new TripDraft(user,
                List.of(a1.getId(), b1.getId(), c2.getId(), d1.getId(), e1.getId(), a2.getId(), -100L), "섞인 초안");
        draft.markReady("{}", "{}");
        draft = drafts.save(draft);

        assertSameAsLegacy(bearer, draft.getId());
        assertThat(currentJobIdsOf(bearer, draft.getId()))
                .containsExactly(a2.getId(), b2.getId(), c1.getId(), d1.getId(), e1.getId(), -100L);
    }

    @Test
    void 남의_초안과_없는_초안은_404이고_인증이_없으면_401이다() throws Exception {
        User owner = user();
        User stranger = user();
        TripDraft draft = readyDraftWithSaved(owner, 1);
        mvc.perform(get("/api/trip-drafts/" + draft.getId()).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/trip-drafts/" + Long.MAX_VALUE).header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/trip-drafts/" + draft.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 초안_상세로_예열한_뒤_탈퇴하면_같은_토큰은_기존_초안과_없는_초안_모두_401이다() throws Exception {
        User owner = user();
        String bearer = bearer(owner);
        TripDraft draft = readyDraftWithSaved(owner, 1);
        read(bearer, draft.getId()); // 사용자 확인을 메모리에 기억시킨다(ADR 0036)

        // 테스트 트랜잭션이 없으므로 탈퇴가 실제로 커밋되고, 커밋 뒤 기억에서 빠진다.
        userAccountService.withdraw(users.findById(owner.getId()).orElseThrow());

        mvc.perform(get("/api/trip-drafts/" + draft.getId()).header("Authorization", bearer))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/trip-drafts/" + Long.MAX_VALUE).header("Authorization", bearer))
                .andExpect(status().isUnauthorized());
    }
}
