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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.SocketTimeoutException;
import java.time.LocalTime;
import java.util.ArrayList;
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
    @MockitoBean private DraftJobQueue queue;
    @MockitoBean private ChatModel model;
    @MockitoBean private OpeningHoursService hours;

    private final ObjectMapper json = new ObjectMapper();
    private final List<Long> queued = new ArrayList<>();
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
    void 첫_요청이_늦게_끝나면_두_결과가_모두_목록에_남는다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            return answer("11:00", "12:00");
        }).thenReturn(answer("13:00", "14:00"));

        long first = submit("당일치기 오전 위주");
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> slow = executor.submit(() -> planner.processNow(first));
            try {
                assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
                assertThat(detail(first).path("status").asText()).isEqualTo("PROCESSING");
                long second = submit("당일치기 오후 위주");
                assertThat(queued).containsExactly(first, second);
                assertThat(cards()).containsExactly(second, first, originalId);

                planner.processNow(second);
                assertReadyAt(second, "13:00");
                assertThat(detail(first).path("status").asText()).isEqualTo("PROCESSING");
                assertThat(cards()).containsExactly(second, first);
                release.countDown();
                slow.get(30, TimeUnit.SECONDS);

                assertThat(cards()).containsExactly(second, first);
                assertReadyAt(first, "11:00");
                assertReadyAt(second, "13:00");
                verify(model, times(2)).call(any(Prompt.class));
            } finally {
                release.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 접수_응답을_버리고_같은_요청을_재전송하면_두_초안이_준비된다(boolean finishedBeforeRetry) throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(answer("11:00", "12:00"), answer("13:00", "14:00"));
        // 앱이 첫 응답을 소비하지 않는다. 작업자는 큐로 전달받은 번호만 사용한다.
        create("당일치기").andExpect(status().isAccepted());
        long first = queued.getFirst();
        if (finishedBeforeRetry) {
            planner.processNow(first);
            assertThat(cards()).containsExactly(first);
        }
        long retry = submit("당일치기");
        assertThat(queued).containsExactly(first, retry);
        assertThat(retry).isNotEqualTo(first);
        if (!finishedBeforeRetry) {
            planner.processNow(first);
        }
        planner.processNow(retry);
        assertReadyAt(first, "11:00");
        assertReadyAt(retry, "13:00");
        assertThat(cards()).containsExactly(retry, first);
        verify(model, times(2)).call(any(Prompt.class));
    }

    private ResultActions create(String message) throws Exception {
        return mvc.perform(post("/api/trip-drafts").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                        "jobIds", List.of(jobId), "message", message, "replacesDraftId", originalId))));
    }

    private long submit(String message) throws Exception {
        JsonNode accepted = json.readTree(create(message).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(accepted.path("status").asText()).isEqualTo("PENDING");
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
