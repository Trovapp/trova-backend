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
import com.trova.backend.service.TripPlannerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import com.trova.backend.service.DraftAdmissionService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.SocketTimeoutException;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private ProcessingJobRepository jobs;
    @Autowired private SavedPlaceRepository places;
    @Autowired private TripDraftRepository drafts;
    @Autowired private TripPlannerService planner;
    @Autowired private JdbcTemplate jdbc;
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
    @ValueSource(booleans = {false, true})
    void 동시_요청은_한_건만_저장하고_같은_키면_재생하고_다른_키면_충돌한다(boolean sameKey) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        String key = UUID.randomUUID().toString();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<MockHttpServletResponse>> calls = new ArrayList<>();
            for (String requestKey : List.of(key, sameKey ? key : UUID.randomUUID().toString())) {
                calls.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return create("당일치기", requestKey).andReturn().getResponse();
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
            assertThat(cards()).containsExactly(first, originalId);
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
    void 입력_대기도_진행_중이며_직접_만든_원본의_홈_노출은_유지한다() throws Exception {
        TripDraft previous = drafts.findById(originalId).orElseThrow();
        Long listed = originalId;
        originalId = drafts.save(new TripDraft(previous.getUser(), List.of(jobId), "직접 만든 원본")).getId();
        long first = submit("당일치기");
        TripDraft waiting = drafts.findById(first).orElseThrow();
        waiting.markNeedsInput("지역을 고르세요", "{}");
        drafts.save(waiting);
        assertConflict(create("당일치기", null).andReturn().getResponse(), first);
        assertThat(cards()).containsExactly(listed);
        originalId = listed;
        assertThat(submit("다른 원본")).isNotEqualTo(first);
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
        MockHttpServletRequestBuilder request = post("/api/trip-drafts").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                        "jobIds", List.of(jobId), "message", message, "replacesDraftId", originalId)));
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
