package com.trova.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.errors.ApiException;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.entity.User;
import com.trova.backend.planner.DraftGenerator;
import com.trova.backend.planner.OpeningHoursService;
import com.trova.backend.queue.DraftJobQueue;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.service.DraftAdmissionService;
import com.trova.backend.service.OrphanedJobRecoveryService;
import com.trova.backend.service.TripPlannerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:replan-exception-qa;MODE=PostgreSQL",
        "app.queue.requeue.enabled=false"
})
@AutoConfigureMockMvc
class TripDraftReplacementQaTest {
    // 명시적으로 선택한 로컬 일회용 DB에서 같은 HTTP 회귀 테스트와 지연 제약 검사를 실행한다.
    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        if ("1".equals(System.getenv("TROVA_DRAFT_POSTGRES_QA"))) {
            registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:56553/trova_draft_qa");
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> "draft_qa");
            registry.add("spring.datasource.password", () -> System.getenv("TROVA_DRAFT_QA_PASSWORD"));
        }
    }

    @Autowired private PlatformTransactionManager transactions;
    @Autowired private DataSource dataSource;
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private ProcessingJobRepository jobs;
    @Autowired private SavedPlaceRepository places;
    @Autowired private TripDraftRepository drafts;
    @Autowired private TripPlannerService planner;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrphanedJobRecoveryService recovery;
    @Autowired private DraftAdmissionService admissions;
    @MockitoBean private DraftJobQueue queue;
    @MockitoBean private ChatModel model;
    @MockitoBean private OpeningHoursService hours;

    private final ObjectMapper json = new ObjectMapper();
    private final List<Long> queued = new CopyOnWriteArrayList<>();
    private String bearer;
    private Long jobId;
    private Long placeId;
    private Long originalId;

    @BeforeEach
    void 준비된_원본을_저장한다() {
        User user = users.save(new User("google", "qa-" + UUID.randomUUID(), "QA", null));
        bearer = "Bearer " + jwt.issue(user);
        ProcessingJob job = new ProcessingJob(user, "https://example.invalid/qa", SourcePlatform.YOUTUBE);
        job.markDone();
        job = jobs.save(job);
        jobId = job.getId();
        SavedPlace place = places.save(new SavedPlace(job, user, "공원", "서울", "attraction", 37.5, 127.0));
        placeId = place.getId();
        DraftGenerator.Item visit = new DraftGenerator.Item(placeId, "공원", "attraction",
                LocalTime.of(9, 0), LocalTime.of(10, 0), 37.5, 127.0);
        TripDraft original = TripDraft.auto(user, List.of(jobId), "당일치기");
        original.applyRequest(1, null, "CODE");
        original.markReady("{}", DraftGenerator.toJson(new DraftGenerator.Draft(
                List.of(new DraftGenerator.Day(1, null, List.of(visit))), List.of(), List.of(), List.of())));
        originalId = drafts.save(original).getId();
        doAnswer(call -> queued.add(call.getArgument(0, Long.class))).when(queue).enqueue(anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"503", "timeout"})
    void 생성_실패는_원본을_보존하고_재시도_성공은_원본을_교체한다(String failure) throws Exception {
        JsonNode originalDetail = detail(originalId);
        JsonNode originalCards = read("/auto");
        RuntimeException error = new RuntimeException("모의 AI 오류", failure.equals("503")
                ? new ApiException(503, "UNAVAILABLE", "QA") : new SocketTimeoutException("QA"));
        when(model.call(any(Prompt.class))).thenThrow(error).thenThrow(error).thenThrow(error)
                .thenReturn(answer("11:00", "12:00"));

        long failed = submit("당일치기");
        assertThat(cards()).containsExactly(failed, originalId);
        planner.processNow(failed);

        verify(model, times(3)).call(any(Prompt.class));
        assertThat(drafts.findById(failed).orElseThrow().getStatus()).isEqualTo(TripDraftStatus.FAILED);
        assertThat(detail(failed).path("status").asText()).isEqualTo("FAILED");
        TripDraft original = drafts.findById(originalId).orElseThrow();
        assertThat(original.getDismissedAt()).isNull();
        assertThat(original.getDraftJson()).isEqualTo(originalDetail.path("draftJson").asText());
        assertThat(detail(originalId)).isEqualTo(originalDetail);
        assertThat(read("/auto")).isEqualTo(originalCards);

        long retry = submit("당일치기");
        assertThat(queued).containsExactly(failed, retry);
        planner.processNow(retry);
        assertThat(cards()).containsExactly(retry);
        assertReadyAt(retry, "11:00");
        assertThat(drafts.findById(originalId).orElseThrow().getDismissedAt()).isNotNull();
        assertThat(detail(originalId)).isEqualTo(originalDetail);
    }

    @Test
    void 처리_중_조건을_바꿔도_409로_먼저_시작한_작업을_알린다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            return answer("11:00", "12:00");
        });

        long first = submit("당일치기 오전 위주");
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> slow = executor.submit(() -> planner.processNow(first));
            try {
                assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
                assertThat(detail(first).path("status").asText()).isEqualTo("PROCESSING");
                assertConflict(create("당일치기 오후 위주", null).andReturn().getResponse(), first);
                assertThat(queued).containsExactly(first);
                assertThat(cards()).containsExactly(first, originalId);
            } finally {
                release.countDown();
            }
            slow.get(30, TimeUnit.SECONDS);
        }
        assertThat(cards()).containsExactly(first);
        assertReadyAt(first, "11:00");
        verify(model).call(any(Prompt.class));
        assertThat(submit("당일치기 오후 위주")).isNotEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 접수_응답을_잃어도_같은_키로_재시도하면_READY_초안_하나만_남는다(boolean finishedBeforeRetry) throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(answer("11:00", "12:00"));
        String key = UUID.randomUUID().toString();
        create("당일치기", key).andExpect(status().isAccepted());
        long first = queued.getFirst();
        if (finishedBeforeRetry) {
            planner.processNow(first);
        }
        assertThat(submit("당일치기", key)).isEqualTo(first);
        if (!finishedBeforeRetry) {
            planner.processNow(first);
        }
        assertThat(queued).containsExactly(first);
        assertThat(cards()).containsExactly(first);
        assertReadyAt(first, "11:00");
        verify(model).call(any(Prompt.class));
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void 동시_요청은_한_건만_저장하고_같은_키면_재생하고_다른_키면_충돌한다(
            boolean sameKey, boolean differentSource) throws Exception {
        Long secondSource = differentSource ? manualSource() : originalId;
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        String key = UUID.randomUUID().toString();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<MockHttpServletResponse>> calls = new ArrayList<>();
            for (Long source : List.of(originalId, secondSource)) {
                String requestKey = sameKey ? key : UUID.randomUUID().toString();
                calls.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return create("당일치기", requestKey, source).andReturn().getResponse();
                }));
            }
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            List<MockHttpServletResponse> responses = List.of(calls.getFirst().get(30, TimeUnit.SECONDS),
                    calls.getLast().get(30, TimeUnit.SECONDS));
            assertThat(responses).extracting(MockHttpServletResponse::getStatus)
                    .containsExactlyInAnyOrder(202, sameKey ? 202 : 409);
            assertThat(queued).hasSize(1);
            long first = queued.getFirst();
            for (MockHttpServletResponse response : responses) {
                if (response.getStatus() == 409) {
                    assertConflict(response, first);
                } else {
                    assertThat(json.readTree(response.getContentAsByteArray()).required("draftId").longValue())
                            .isEqualTo(first);
                }
            }
            if (drafts.findById(first).orElseThrow().isListed()) {
                assertThat(cards()).containsExactly(first, originalId);
            } else {
                assertThat(cards()).containsExactly(originalId);
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TROVA_DRAFT_POSTGRES_QA", matches = "1")
    void 커밋_실패는_큐로_가지_않고_큐에서는_별도_연결로_커밋된_행을_읽는다() throws Exception {
        String rejected = "커밋 실패 QA";
        jdbc.execute("""
                create function qa_reject_draft() returns trigger language plpgsql as
                $$ begin raise exception 'qa deferred failure'; end $$
                """);
        jdbc.execute("""
                create constraint trigger qa_reject after insert on trip_drafts deferrable initially deferred
                for each row when (new.message = '커밋 실패 QA') execute function qa_reject_draft()
                """);
        try (Connection observer = dataSource.getConnection();
             PreparedStatement lookup = observer.prepareStatement("select status from trip_drafts where id = ?")) {
            doAnswer(call -> {
                Long id = call.getArgument(0, Long.class);
                lookup.setLong(1, id);
                try (ResultSet rows = lookup.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("PENDING");
                }
                return queued.add(id);
            }).when(queue).enqueue(anyLong());

            assertThatThrownBy(() -> create(rejected, null)).hasStackTraceContaining("qa deferred failure");
            assertThat(queued).isEmpty();
            assertThat(jdbc.queryForObject("select count(*) from trip_drafts where message = ?", Long.class, rejected))
                    .isZero();
            assertThat(submit("정상 접수")).isEqualTo(queued.getFirst());
        } finally {
            jdbc.execute("drop trigger qa_reject on trip_drafts");
            jdbc.execute("drop function qa_reject_draft()");
        }
    }

    @Test
    void 키는_사용자별이고_실패해도_24시간_재생하며_만료_후_새로_접수한다() throws Exception {
        String key = UUID.randomUUID().toString();
        long first = submit("당일치기", key);
        TripDraft draft = drafts.findById(first).orElseThrow();
        draft.markFailed("QA");
        drafts.save(draft);
        assertThat(submit("변경한 조건", key)).isEqualTo(first);
        String owner = bearer;
        User other = users.save(new User("google", "qa-" + UUID.randomUUID(), "QA2", null));
        bearer = "Bearer " + jwt.issue(other);
        create("당일치기", key).andExpect(status().isBadRequest());
        bearer = owner;

        jdbc.update("update trip_drafts set created_at = ? where id = ?",
                LocalDateTime.now().minusHours(25), first);
        long next = submit("재시도", key);
        assertThat(next).isNotEqualTo(first);
        admissions.clearExpiredKeys();
        assertThat(jdbc.queryForObject("select request_key from trip_drafts where id = ?", UUID.class, first)).isNull();
        assertThat(submit("재시도", key)).isEqualTo(next);
        assertThat(queued).containsExactly(first, next);
    }

    @Test
    void 두_사용자는_같은_키로_각자의_초안을_만들고_재생한다() throws Exception {
        String key = UUID.randomUUID().toString();
        String firstToken = bearer;
        Long firstJob = jobId;
        Long firstSource = originalId;
        long first = submit("사용자 하나", key);

        User other = users.save(new User("google", "qa-" + UUID.randomUUID(), "QA2", null));
        ProcessingJob job = new ProcessingJob(other, "https://example.invalid/other", SourcePlatform.YOUTUBE);
        job.markDone();
        jobId = jobs.save(job).getId();
        originalId = drafts.save(TripDraft.auto(other, List.of(jobId), "다른 원본")).getId();
        bearer = "Bearer " + jwt.issue(other);
        long second = submit("사용자 둘", key);
        assertThat(second).isNotEqualTo(first);
        assertThat(submit("재전송", key)).isEqualTo(second);
        bearer = firstToken;
        jobId = firstJob;
        originalId = firstSource;
        assertThat(submit("재전송", key)).isEqualTo(first);
        assertThat(queued).containsExactly(first, second);
    }

    @Test
    void 정확히_24시간에_키가_만료돼도_진행_중이면_409다() throws Exception {
        UUID key = UUID.randomUUID();
        long first = submit("당일치기", key.toString());
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24).withNano(0);
        jdbc.update("update trip_drafts set created_at = ? where id = ?", cutoff, first);
        Long owner = drafts.findById(first).orElseThrow().getUser().getId();
        assertThat(drafts.findRecentRequest(owner, key, cutoff.minusSeconds(1))).contains(first);
        assertThat(drafts.findRecentRequest(owner, key, cutoff)).isEmpty();
        assertConflict(create("재시도", key.toString()).andReturn().getResponse(), first);
        assertThat(queued).containsExactly(first);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 질문을_떠난_사용자가_키_없이_다시_짜면_대기_초안을_정리한다(boolean listed) throws Exception {
        Long home = originalId;
        if (!listed) {
            originalId = manualSource();
        }
        JsonNode original = detail(originalId);
        String key = UUID.randomUUID().toString();
        long first = submit("당일치기", key);
        TripDraft waiting = drafts.findById(first).orElseThrow();
        waiting.markNeedsInput("지역을 고르세요", "{}");
        drafts.save(waiting);
        assertThat(submit("응답 복구", key)).isEqualTo(first);
        create(" ", null).andExpect(status().isBadRequest());
        assertThat(detail(first).path("status").asText()).isEqualTo("NEEDS_INPUT");

        long retry = submit("조건 변경");
        assertThat(queued).containsExactly(first, retry);
        assertThat(detail(first).path("status").asText()).isEqualTo("FAILED");
        mvc.perform(post("/api/trip-drafts/" + first + "/answer").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"choice\":\"SPLIT\"}"))
                .andExpect(status().isConflict());
        assertThat(detail(originalId)).isEqualTo(original);
        assertThat(cards()).containsExactlyElementsOf(listed ? List.of(retry, home) : List.of(home));
    }

    @Test
    void 늦게_커밋된_닫기가_교체된_초안의_질문을_되살리지_않는다() throws Exception {
        long first = submit("당일치기");
        TripDraft waiting = drafts.findById(first).orElseThrow();
        waiting.markNeedsInput("지역을 고르세요", "{}");
        drafts.save(waiting);
        TransactionTemplate replacement = new TransactionTemplate(transactions);
        replacement.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            TripDraft closing = drafts.findById(first).orElseThrow();
            assertThat(closing.getStatus()).isEqualTo(TripDraftStatus.NEEDS_INPUT);
            replacement.executeWithoutResult(inner -> assertThat(admissions.admit(closing.getUser().getId(),
                    List.of(jobId), "새 조건", originalId, null).outcome()).isEqualTo(DraftAdmissionService.Outcome.CREATED));
            closing.dismiss();
        });
        assertThat(detail(first).path("status").asText()).isEqualTo("FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PROCESSING"})
    void 배포_전_비노출_원본을_알_수_없는_작업이_끝나야_접수한다(String status) throws Exception {
        Long listed = originalId;
        originalId = manualSource();
        Long userId = drafts.findById(originalId).orElseThrow().getUser().getId();
        // 구버전 INSERT에는 원본 번호와 신규 접수 메타데이터가 없다.
        jdbc.update("""
                insert into trip_drafts (user_id, job_ids, message, status, auto_created, created_at, updated_at)
                values (?, ?, '구버전 대체 작업', ?, false, current_timestamp, current_timestamp)
                """, userId, jobId.toString(), status);
        MockHttpServletResponse response = create("다시 짜기", null).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("30");
        assertThat(json.readTree(response.getContentAsByteArray()).required("code").asText())
                .isEqualTo("DRAFT_LEGACY_JOB_IN_PROGRESS");
        assertThat(json.readTree(response.getContentAsByteArray()).has("activeDraftId")).isFalse();
        assertThat(queued).isEmpty();
        Long unlisted = originalId;
        originalId = listed;
        long independent = submit("다른 원본");
        originalId = unlisted;
        jdbc.update("update trip_drafts set status = 'READY' where user_id = ? and message = '구버전 대체 작업'", userId);
        long retry = submit("다시 짜기");
        assertThat(queued).containsExactly(independent, retry);
    }

    private Long manualSource() {
        User owner = drafts.findById(originalId).orElseThrow().getUser();
        TripDraft manual = new TripDraft(owner, List.of(jobId), "직접 만든 원본");
        manual.markReady("{}", "{}");
        return drafts.save(manual).getId();
    }

    @Test
    void 정리_직후_늦게_끝난_작업은_실패로_남고_다음_성공까지_원본이_보인다() throws Exception {
        long first = submit("당일치기");
        JsonNode original = detail(originalId);
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            jdbc.update("update trip_drafts set updated_at = ? where id = ?", LocalDateTime.now().minusHours(1), first);
            recovery.failStaleJobs();
            assertThat(detail(first).path("status").asText()).isEqualTo("FAILED");
            submit("당일치기");
            return answer("11:00", "12:00");
        }).thenReturn(answer("13:00", "14:00"));

        planner.processNow(first);
        long retry = queued.getLast();
        assertThat(retry).isNotEqualTo(first);
        assertThat(detail(first).path("status").asText()).isEqualTo("FAILED");
        assertThat(detail(originalId)).isEqualTo(original);
        assertThat(cards()).containsExactly(retry, originalId);
        planner.processNow(retry);
        assertReadyAt(retry, "13:00");
        assertThat(cards()).containsExactly(retry);
    }

    @Test
    void 잘못된_요청_키는_작업을_만들지_않는다() throws Exception {
        create("당일치기", "1-1-1-1-1").andExpect(status().isBadRequest());
        assertThat(queued).isEmpty();
        assertThat(cards()).containsExactly(originalId);
    }

    private void assertConflict(MockHttpServletResponse response, long first) throws Exception {
        assertThat(response.getStatus()).isEqualTo(409);
        JsonNode body = json.readTree(response.getContentAsByteArray());
        assertThat(body.required("code").asText()).isEqualTo("DRAFT_REPLACEMENT_IN_PROGRESS");
        assertThat(body.required("activeDraftId").longValue()).isEqualTo(first);
        assertThat(body.required("status").intValue()).isEqualTo(409);
        assertThat(body.required("message").asText()).isNotBlank();
        assertThat(body.required("traceId").asText()).isNotBlank().isEqualTo(response.getHeader("X-Trace-Id"));
    }

    private ResultActions create(String message, String key) throws Exception {
        return create(message, key, originalId);
    }

    private ResultActions create(String message, String key, Long sourceId) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/trip-drafts").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                        "jobIds", List.of(jobId), "message", message, "replacesDraftId", sourceId)));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private long submit(String message) throws Exception {
        return submit(message, null);
    }

    private long submit(String message, String key) throws Exception {
        JsonNode accepted = json.readTree(create(message, key).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(accepted.required("status").asText()).isEqualTo("PENDING");
        return accepted.required("draftId").longValue();
    }

    private JsonNode read(String suffix) throws Exception {
        return json.readTree(mvc.perform(get("/api/trip-drafts" + suffix).header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private JsonNode detail(long id) throws Exception {
        return read("/" + id);
    }

    private List<Long> cards() throws Exception {
        return read("/auto").findValues("draftId").stream().map(JsonNode::longValue).toList();
    }

    private void assertReadyAt(long id, String start) throws Exception {
        JsonNode result = detail(id);
        assertThat(result.path("status").asText()).isEqualTo("READY");
        String plan = result.required("draftJson").asText();
        assertThat(json.readTree(plan).at("/days/0/items/0/start").asText()).isEqualTo(start);
        TripDraft stored = drafts.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TripDraftStatus.READY);
        assertThat(stored.getReplacesDraftId()).isEqualTo(originalId);
        assertThat(stored.getDraftJson()).isEqualTo(plan);
    }

    private ChatResponse answer(String start, String end) {
        String content = """
                {"days":[{"day":1,"items":[{"placeId":%d,"start":"%s","end":"%s"}]}],"excluded":[]}
                """.formatted(placeId, start, end);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
