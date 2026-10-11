package com.trova.backend.controller;

import com.trova.backend.repository.ServerMetricSnapshotRepository;
import com.trova.backend.entity.ServerMetricSnapshot;
import com.trova.backend.repository.ApiCallLogRepository;
import com.trova.backend.entity.ApiCallLog;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.support.AdminWebAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 대시보드 집계 API(#161). 날짜를 정하려고 저장 뒤 created_at을 직접 고친다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"app.admin.users=google:dash-admin", "app.instance-name=test-a"})
class AdminDashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private ApiCallLogRepository apiCallLogRepository;

    @Autowired
    private ServerMetricSnapshotRepository snapshotRepository;

    @Autowired
    private TripDraftRepository tripDraftRepository;

    @Autowired
    private JwtService jwtService;

    private RequestPostProcessor loginAs(String sub) {
        return AdminWebAuth.adminWebToken(jwtService, userRepository, sub);
    }

    private User admin() {
        return userRepository.findByProviderAndProviderUserId("google", "dash-admin")
                .orElseGet(() -> userRepository.save(new User("google", "dash-admin", "관리자", null)));
    }

    private void setCreatedAt(String table, Long id, LocalDateTime at) {
        jdbcTemplate.update("update " + table + " set created_at = ? where id = ?", at, id);
    }

    @Test
    void 관리자가_아니면_사용_데이터를_볼_수_없다() throws Exception {
        userRepository.save(new User("google", "dash-normal", "일반", null));
        mockMvc.perform(get("/api/admin/dashboard/usage").with(loginAs("dash-normal")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 기간은_7_14_30일만_받는다() throws Exception {
        admin();
        mockMvc.perform(get("/api/admin/dashboard/usage?days=0").with(loginAs("dash-admin"))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/dashboard/usage?days=31").with(loginAs("dash-admin"))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/dashboard/usage?days=abc").with(loginAs("dash-admin"))).andExpect(status().isBadRequest());
    }

    @Test
    void 사용_데이터는_빈_날을_0으로_채운_일별_가입자_수를_준다() throws Exception {
        admin();
        User older = userRepository.save(new User("google", "dash-u1", "가입1", null));
        User today = userRepository.save(new User("google", "dash-u2", "가입2", null));
        setCreatedAt("users", older.getId(), LocalDate.now().minusDays(2).atTime(10, 0));
        setCreatedAt("users", today.getId(), LocalDate.now().atTime(0, 5));
        // 관리자 계정도 오늘 가입으로 잡힌다 — 오늘 2명(가입2 + 관리자), 2일 전 1명.

        mockMvc.perform(get("/api/admin/dashboard/usage?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.newUsers.length()").value(7))
                .andExpect(jsonPath("$.newUsers[6].date").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.newUsers[6].count").value(2))
                .andExpect(jsonPath("$.newUsers[4].count").value(1))
                .andExpect(jsonPath("$.newUsers[5].count").value(0))
                .andExpect(jsonPath("$.trips.length()").value(7));
    }

    @Test
    void 사용_데이터의_자동_초안은_자동으로_만든_초안만_센다() throws Exception {
        User admin = admin();
        tripDraftRepository.save(TripDraft.auto(admin, List.of(1L), "자동 초안"));
        tripDraftRepository.save(new TripDraft(admin, List.of(1L), "수동 초안"));

        mockMvc.perform(get("/api/admin/dashboard/usage?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoDrafts[6].count").value(1));
    }

    private ProcessingJob job(User user, String url, String status, String error, LocalDateTime createdAt, LocalDateTime updatedAt) {
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(user, url, SourcePlatform.YOUTUBE));
        jdbcTemplate.update("update processing_jobs set status = ?, error_message = ?, created_at = ?, updated_at = ? where id = ?",
                status, error, createdAt, updatedAt, job.getId());
        return job;
    }

    private ProcessingJob analyzedJob(User user, String url, String analysisStatus, LocalDateTime createdAt,
                                      LocalDateTime analysisFinishedAt, String status, LocalDateTime updatedAt) {
        ProcessingJob job = job(user, url, status, "일정 생성 실패", createdAt, updatedAt);
        jdbcTemplate.update("update processing_jobs set analysis_status = ?, analysis_finished_at = ? where id = ?",
                analysisStatus, analysisFinishedAt, job.getId());
        return job;
    }

    @Test
    void 파이프라인은_일별_성공_실패와_처리_시간_실패_이유를_준다() throws Exception {
        User admin = admin();
        LocalDateTime today = LocalDate.now().atTime(0, 10);
        job(admin, "https://youtu.be/dashA1", "DONE", null, today, today.plusSeconds(20));
        job(admin, "https://youtu.be/dashA2", "DONE", null, today, today.plusSeconds(40));
        job(admin, "https://youtu.be/dashA3", "FAILED", ProcessingJob.SOURCE_RATE_LIMIT_MESSAGE, today, today.plusSeconds(5));
        job(admin, "https://youtu.be/dashA4", "FAILED", null, today, today.plusSeconds(5));
        // 기간(7일) 하루 앞 작업은 처리 시간·실패 이유에 넣지 않는다.
        LocalDateTime beforePeriod = LocalDate.now().minusDays(7).atTime(12, 0);
        job(admin, "https://youtu.be/dashOld1", "DONE", null, beforePeriod, beforePeriod.plusSeconds(1000));
        job(admin, "https://youtu.be/dashOld2", "FAILED", ProcessingJob.NO_PLACES_MESSAGE, beforePeriod, beforePeriod.plusSeconds(5));

        mockMvc.perform(get("/api/admin/dashboard/pipeline?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily.length()").value(7))
                .andExpect(jsonPath("$.daily[6].total").value(4))
                .andExpect(jsonPath("$.daily[6].done").value(2))
                .andExpect(jsonPath("$.daily[6].failed").value(2))
                .andExpect(jsonPath("$.durationP50Seconds").value(20))
                .andExpect(jsonPath("$.durationP90Seconds").value(40))
                .andExpect(jsonPath("$.failureReasons[?(@.reason == 'SOURCE_RATE_LIMITED')].count").value(1))
                .andExpect(jsonPath("$.failureReasons[?(@.reason == 'OTHER')].count").value(1))
                .andExpect(jsonPath("$.failureReasons[?(@.reason == 'NO_PLACES')]").isEmpty());
    }

    @Test
    void 분석_뒤_일정_생성으로_바뀐_작업은_처음_분석_결과와_시각으로_센다() throws Exception {
        // 일정 생성은 끝난 같은 작업의 status·updated_at을 다시 바꾼다 — 그대로 세면 처리 시간이 며칠로 부풀고
        // 일정 생성 실패가 분석 실패로 섞인다(리뷰 지적, #161).
        User admin = admin();
        LocalDateTime created = LocalDate.now().atTime(0, 10);
        analyzedJob(admin, "https://youtu.be/dashItin", "DONE", created, created.plusSeconds(30),
                "FAILED", LocalDateTime.now());

        mockMvc.perform(get("/api/admin/dashboard/pipeline?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily[6].done").value(1))
                .andExpect(jsonPath("$.daily[6].failed").value(0))
                .andExpect(jsonPath("$.durationP90Seconds").value(30))
                .andExpect(jsonPath("$.failureReasons").isEmpty());
        mockMvc.perform(get("/api/admin/dashboard/summary").with(loginAs("dash-admin")))
                .andExpect(jsonPath("$.cards[?(@.key == 'failures24h')].value").value(0));
    }

    @Test
    void 오래_갱신되지_않은_진행_중_작업은_멈춘_작업으로_보여주되_개인정보는_빼고_준다() throws Exception {
        User admin = admin();
        LocalDateTime old = LocalDateTime.now().minusMinutes(9);
        ProcessingJob stuck = job(admin, "https://youtu.be/dashStuck", "PROCESSING", null, old, old);
        ProcessingJob pending = job(admin, "https://youtu.be/dashPending", "PENDING", null, old.plusSeconds(1), old.plusSeconds(1));
        job(admin, "https://youtu.be/dashFresh", "PROCESSING", null, LocalDateTime.now(), LocalDateTime.now());
        // 오래됐어도 끝난 작업은 멈춘 작업이 아니다.
        job(admin, "https://youtu.be/dashOldDone", "DONE", null, old, old);
        job(admin, "https://youtu.be/dashOldFailed", "FAILED", null, old, old);

        mockMvc.perform(get("/api/admin/dashboard/pipeline?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stuckJobs.length()").value(2))
                .andExpect(jsonPath("$.stuckJobs[0].id").value(stuck.getId()))
                .andExpect(jsonPath("$.stuckJobs[1].id").value(pending.getId()))
                .andExpect(jsonPath("$.stuckJobs[0].sourceUrl").doesNotExist());
    }

    @Test
    void 작업이_없으면_파이프라인은_0과_빈_목록을_준다() throws Exception {
        admin();
        mockMvc.perform(get("/api/admin/dashboard/pipeline?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily.length()").value(7))
                .andExpect(jsonPath("$.durationP90Seconds").doesNotExist());
    }

    @Test
    void AI_탭은_일별_호출_실패_토큰과_작업당_호출_수를_준다() throws Exception {
        User admin = admin();
        Long job1 = job(admin, "https://youtu.be/dashAi1", "DONE", null, LocalDateTime.now(), LocalDateTime.now()).getId();
        Long job2 = job(admin, "https://youtu.be/dashAi2", "DONE", null, LocalDateTime.now(), LocalDateTime.now()).getId();
        apiCallLogRepository.save(new ApiCallLog("gemini", "extract_places", job1, 900L, true, null, 100, 50, 150));
        apiCallLogRepository.save(new ApiCallLog("gemini", "extract_places", job1, 800L, false, "429", null, null, null));
        apiCallLogRepository.save(new ApiCallLog("kakao", "address_search", job2, 60L, true, null, null, null, null));
        apiCallLogRepository.save(new ApiCallLog("gemini", "conversation-turn", null, 500L, true, null, 10, 5, 15));

        mockMvc.perform(get("/api/admin/dashboard/ai?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily.length()").value(7))
                .andExpect(jsonPath("$.daily[6].calls").value(4))
                .andExpect(jsonPath("$.daily[6].failures").value(1))
                .andExpect(jsonPath("$.daily[6].tokens").value(165))
                .andExpect(jsonPath("$.callsPerJob").value(1.5))
                .andExpect(jsonPath("$.byOperation[0].operation").value("extract_places"))
                .andExpect(jsonPath("$.byOperation[0].calls").value(2));
    }

    @Test
    void 작업당_호출_수는_영상_분석_작업의_호출만_센다() throws Exception {
        // 추천 태깅·리뷰 요약은 job_id 자리에 System.nanoTime()을 넣어 기록한다 — 작업으로 세면 평균이 낮아진다(리뷰 지적, #161).
        User admin = admin();
        Long job1 = job(admin, "https://youtu.be/dashAi3", "DONE", null, LocalDateTime.now(), LocalDateTime.now()).getId();
        for (int i = 0; i < 4; i++) {
            apiCallLogRepository.save(new ApiCallLog("gemini", "extract_places", job1, 100L, true, null, null, null, null));
        }
        apiCallLogRepository.save(new ApiCallLog("gemini", "place_tagging", System.nanoTime(), 100L, true, null, null, null, null));
        apiCallLogRepository.save(new ApiCallLog("gemini", "review_summary", System.nanoTime(), 100L, true, null, null, null, null));
        // 기간(7일) 하루 앞 호출은 세지 않는다.
        ApiCallLog old = apiCallLogRepository.save(new ApiCallLog("gemini", "extract_places", job1, 100L, true, null, null, null, null));
        setCreatedAt("api_call_logs", old.getId(), LocalDate.now().minusDays(7).atTime(12, 0));

        mockMvc.perform(get("/api/admin/dashboard/ai?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callsPerJob").value(4.0));
    }

    @Test
    void 서버_탭은_이_서버_이름의_스냅샷만_준다() throws Exception {
        admin();
        snapshotRepository.save(new ServerMetricSnapshot("test-a", LocalDateTime.now().minusMinutes(10), 12, 30, 80, 0, 200, 2));
        snapshotRepository.save(new ServerMetricSnapshot("other-local", LocalDateTime.now().minusMinutes(10), 99, 1, 1, 0, 1, 1));

        mockMvc.perform(get("/api/admin/dashboard/server?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceName").value("test-a"))
                .andExpect(jsonPath("$.snapshots.length()").value(1))
                .andExpect(jsonPath("$.snapshots[0].requestCount").value(12))
                .andExpect(jsonPath("$.snapshots[0].p90Ms").value(80));
    }

    @Test
    void 요약은_카드_4개를_주고_기준을_넘으면_경고한다() throws Exception {
        User admin = admin();
        LocalDateTime now = LocalDateTime.now();
        // 기준값(5)과 같으면 경고, 24시간보다 오래된 실패는 세지 않는다.
        for (int i = 0; i < 5; i++) {
            job(admin, "https://youtu.be/dashF" + i, "FAILED", null, now.minusHours(1), now.minusHours(1));
        }
        job(admin, "https://youtu.be/dashFOld", "FAILED", null, now.minusHours(25), now.minusHours(25));

        mockMvc.perform(get("/api/admin/dashboard/summary").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(4))
                .andExpect(jsonPath("$.cards[?(@.key == 'failures24h')].value").value(5))
                .andExpect(jsonPath("$.cards[?(@.key == 'failures24h')].alert").value(true))
                .andExpect(jsonPath("$.cards[?(@.key == 'p90LastHour')].value").value(hasItem(nullValue())))
                .andExpect(jsonPath("$.cards[?(@.key == 'p90LastHour')].alert").value(false))
                // 같은 Gemini 키를 다른 DB의 서버(로컬)도 쓰므로 "이 DB에 기록된 호출만"임을 분명히 한다(2026-10-04 한도 소진).
                .andExpect(jsonPath("$.cards[?(@.key == 'geminiToday')].note").value(hasItem(containsString("이 DB에 기록된 호출만"))));
    }

    @Test
    void 요약의_p90은_최근_1시간_이_서버_스냅샷의_최댓값이고_기준을_넘으면_경고한다() throws Exception {
        admin();
        LocalDateTime now = LocalDateTime.now();
        snapshotRepository.save(new ServerMetricSnapshot("test-a", now.minusMinutes(20), 10, 50, 800, 0, 100, 1));
        snapshotRepository.save(new ServerMetricSnapshot("test-a", now.minusMinutes(10), 10, 50, 1200, 0, 100, 1));
        snapshotRepository.save(new ServerMetricSnapshot("other-local", now.minusMinutes(10), 10, 50, 5000, 0, 100, 1));
        snapshotRepository.save(new ServerMetricSnapshot("test-a", now.minusHours(2), 10, 50, 9000, 0, 100, 1));

        mockMvc.perform(get("/api/admin/dashboard/summary").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards[?(@.key == 'p90LastHour')].value").value(1200))
                .andExpect(jsonPath("$.cards[?(@.key == 'p90LastHour')].alert").value(true));
    }

    @Test
    void 요약의_오늘_제미나이_호출은_태평양_자정_이후_gemini_호출만_센다() throws Exception {
        admin();
        LocalDateTime pacificMidnight = LocalDate.now(ZoneId.of("America/Los_Angeles"))
                .atStartOfDay(ZoneId.of("America/Los_Angeles"))
                .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        apiCallLogRepository.save(new ApiCallLog("gemini", "extract_places", null, 100L, true, null, null, null, null));
        ApiCallLog before = apiCallLogRepository.save(new ApiCallLog("gemini", "extract_places", null, 100L, true, null, null, null, null));
        setCreatedAt("api_call_logs", before.getId(), pacificMidnight.minusMinutes(1));
        apiCallLogRepository.save(new ApiCallLog("kakao", "address_search", null, 100L, true, null, null, null, null));

        mockMvc.perform(get("/api/admin/dashboard/summary").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards[?(@.key == 'geminiToday')].value").value(1));
    }

    @Test
    void 영상_분석_작업_호출이_없으면_작업당_호출_수는_비어_있다() throws Exception {
        admin();
        apiCallLogRepository.save(new ApiCallLog("gemini", "place_tagging", System.nanoTime(), 100L, true, null, null, null, null));

        mockMvc.perform(get("/api/admin/dashboard/ai?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callsPerJob").value(nullValue()));
    }

    private TripDraft draft(User user, boolean auto, String status, LocalDateTime createdAt, LocalDateTime updatedAt) {
        TripDraft draft = tripDraftRepository.save(auto ? TripDraft.auto(user, List.of(1L), "초안") : new TripDraft(user, List.of(1L), "초안"));
        jdbcTemplate.update("update trip_drafts set status = ?, created_at = ?, updated_at = ? where id = ?",
                status, createdAt, updatedAt, draft.getId());
        return draft;
    }

    @Test
    void 파이프라인은_일정_초안의_실패_수와_멈춘_초안을_준다() throws Exception {
        // 설계에서 PR③(화면)과 함께 넣기로 한 일정 초안(trip_drafts)의 멈춤·실패(#161·#5 리뷰 지적).
        User admin = admin();
        LocalDateTime today = LocalDate.now().atTime(0, 10);
        LocalDateTime old = LocalDateTime.now().minusMinutes(9);
        draft(admin, true, "READY", today, today);
        draft(admin, true, "FAILED", today, today);
        TripDraft stuck = draft(admin, true, "PROCESSING", old, old);
        draft(admin, false, "PENDING", LocalDateTime.now(), LocalDateTime.now());
        // 오래됐어도 끝난 초안은 멈춘 것이 아니다. 기간(7일) 하루 앞 초안은 세지 않는다.
        draft(admin, false, "FAILED", LocalDate.now().minusDays(7).atTime(12, 0), old);

        mockMvc.perform(get("/api/admin/dashboard/pipeline?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drafts.total").value(4))
                .andExpect(jsonPath("$.drafts.failed").value(1))
                .andExpect(jsonPath("$.drafts.stuck.length()").value(1))
                .andExpect(jsonPath("$.drafts.stuck[0].id").value(stuck.getId()))
                .andExpect(jsonPath("$.drafts.stuck[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$.drafts.stuck[0].autoCreated").value(true))
                .andExpect(jsonPath("$.drafts.stuck[0].message").doesNotExist());
        mockMvc.perform(get("/api/admin/dashboard/summary").with(loginAs("dash-admin")))
                .andExpect(jsonPath("$.cards[?(@.key == 'stuckJobs')].value").value(1));
    }
}
