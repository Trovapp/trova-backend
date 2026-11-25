package com.trova.backend.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.controller.ConversationController;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.repository.*;
import com.trova.backend.service.ApiCallLogService;
import com.trova.backend.service.CurrentUserService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConversationStreamContractTest {
    private final GeminiChatClient gemini = mock(GeminiChatClient.class);
    private final ConversationToolExecutor tools = mock(ConversationToolExecutor.class);
    private final ConversationSessionStore sessions = new ConversationSessionStore();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final User user = mock(User.class);
    private final TripRepository trips = mock(TripRepository.class);
    private ConversationService service;
    private ConversationStreams streams;
    private MockMvc mvc;
    private ConversationState state;
    private final CountDownLatch saved = new CountDownLatch(1);
    private final AlternativeCandidate candidate = new AlternativeCandidate(5L, "g5", "조용한 카페", "cafe", 4.5, 100,
            37.5, 127.0, "서울", 0.2, 3, false, null, "가까워요");

    @BeforeEach void setup() {
        when(user.getId()).thenReturn(1L);
        Trip trip = new Trip(user, "여행", null, null);
        when(trips.findById(10L)).thenReturn(Optional.of(trip));
        CurrentUserService users = mock(CurrentUserService.class);
        when(users.resolve(any())).thenReturn(user);
        service = spy(new ConversationService(gemini, tools, mock(ApiCallLogService.class)));
        doAnswer(invocation -> {
            try { return invocation.callRealMethod(); }
            finally { saved.countDown(); }
        }).when(service).sendMessage(any(), any(), anyString(), any());
        streams = new ConversationStreams(service, meters, 3, 256);
        mvc = MockMvcBuilders.standaloneSetup(new ConversationController(users, sessions, service,
                mock(TripPlaceRepository.class), trips, mock(ItineraryRepository.class), streams))
                .addFilters(new ConversationSseMvc()).build();
        state = sessions.create("s", 1L, 10L, 100L, null, null);
    }

    @AfterEach void close() { streams.shutdown(); meters.close(); MDC.clear(); }

    private MvcResult stream() throws Exception {
        return mvc.perform(post("/api/conversations/s/messages/stream")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"message\":\"찾아줘\"}"))
                .andExpect(status().isOk()).andExpect(request().asyncStarted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Accel-Buffering", "no")).andReturn();
    }

    private String finish(MvcResult result) throws Exception {
        result.getAsyncResult(5000);
        return mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void toolTurn() {
        var call = new GeminiChatClient.FunctionCall("find_alternatives", Map.of(), "signature");
        when(gemini.sendMessage(any(), any(), any())).thenReturn(new GeminiChatClient.ChatResult(call, null));
        when(tools.execute(any(), any(), any(), any())).thenReturn(
                new ConversationToolExecutor.ToolExecutionResult(List.of(candidate), Map.of("candidates", List.of())));
    }

    @Test void cards_are_flushed_before_second_Gemini_finishes_and_legacy_contract_and_state_match() throws Exception {
        toolTurn();
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch allowReply = new CountDownLatch(1);
        when(gemini.sendFunctionResult(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            secondEntered.countDown();
            assertThat(allowReply.await(5, TimeUnit.SECONDS)).isTrue();
            return new GeminiChatClient.ChatResult(null, "- \"가까운 카페\"예요");
        });
        MvcResult result = stream();
        try {
            assertThat(secondEntered.await(3, TimeUnit.SECONDS)).isTrue();
            String early = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(early).contains("event:cards", "조용한 카페").doesNotContain("event:reply", "event:done");
        } finally { allowReply.countDown(); }
        String body = finish(result);
        assertThat(events(body)).containsExactly("cards", "reply", "done");
        assertThat(body).contains("가까운 카페예요");
        assertThat(meters.get("conversation.stream.cards").timer().count()).isEqualTo(1);
        assertThat(meters.get("conversation.stream.complete").tag("outcome", "success").timer().count()).isEqualTo(1);

        ConversationState legacy = sessions.create("legacy", 1L, 10L, 100L, null, null);
        String json = mvc.perform(post("/api/conversations/legacy/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"찾아줘\"}"))
                .andExpect(status().isOk()).andExpect(request().asyncNotStarted())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        var tree = mapper.readTree(json);
        assertThat(tree.size()).isEqualTo(4);
        assertThat(tree.get("reply").asText()).isEqualTo("가까운 카페예요");
        assertThat(tree.get("turnCount").asInt()).isEqualTo(1);
        assertThat(tree.get("turnLimitReached").asBoolean()).isFalse();
        assertThat(tree.get("candidates")).isEqualTo(mapper.readTree(data(body, "cards")).get("candidates"));
        assertThat(legacy.getHistory()).isEqualTo(state.getHistory());
        assertThat(legacy.getShownCandidateIds()).isEqualTo(state.getShownCandidateIds());
        assertThat(legacy.getTurnCount()).isEqualTo(state.getTurnCount());
        // 스트림 뒤 기존 경로를 써도 같은 세션의 다음 턴으로 이어진다.
        when(gemini.sendMessage(any(), any(), any())).thenReturn(new GeminiChatClient.ChatResult(null, "네"));
        mvc.perform(post("/api/conversations/s/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"좋아\"}" )).andExpect(jsonPath("$.turnCount").value(2));
    }

    @Test void no_tool_sends_only_reply_and_done() throws Exception {
        when(gemini.sendMessage(any(), any(), any())).thenReturn(new GeminiChatClient.ChatResult(null, "반가워요"));
        String body = finish(stream());
        assertThat(events(body)).containsExactly("reply", "done");
        assertThat(data(body, "reply")).contains("반가워요", "\"turnCount\":1", "\"turnLimitReached\":false");
        assertThat(state.getHistory()).hasSize(2);
        assertThat(meters.find("conversation.stream.cards").timer()).isNull();
    }

    @Test void error_after_cards_keeps_trace_and_has_no_done_or_partial_history() throws Exception {
        toolTurn();
        MDC.put("traceId", "existing-trace");
        when(gemini.sendFunctionResult(any(), any(), any(), any(), any()))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "하루 한도"));
        MvcResult result = stream();
        assertThat(result.getResponse().getHeader("X-Trace-Id")).isEqualTo("existing-trace");
        String body = finish(result);
        assertThat(events(body)).containsExactly("cards", "error");
        var error = new ObjectMapper().readTree(data(body, "error"));
        assertThat(error.get("status").asInt()).isEqualTo(429);
        assertThat(error.get("error").asText()).isEqualTo("Too Many Requests");
        assertThat(error.get("message").asText()).isEqualTo("요청이 많아요. 잠시 후 다시 시도해 주세요.");
        assertThat(error.get("code").asText()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(error.get("traceId").asText()).isEqualTo("existing-trace");
        assertThat(error.get("path").asText()).isEqualTo("/api/conversations/s/messages/stream");
        assertThat(state.getHistory()).isEmpty();
        assertThat(meters.get("conversation.stream.complete").tag("outcome", "error").timer().count()).isEqualTo(1);
    }

    @Test void disconnected_client_does_not_cancel_generation_or_state_save() throws Exception {
        toolTurn();
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch allowReply = new CountDownLatch(1);
        when(gemini.sendFunctionResult(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            secondEntered.countDown();
            assertThat(allowReply.await(5, TimeUnit.SECONDS)).isTrue();
            return new GeminiChatClient.ChatResult(null, "완료했어요");
        });
        MvcResult result = stream();
        try {
            assertThat(secondEntered.await(3, TimeUnit.SECONDS)).isTrue();
            result.getRequest().getAsyncContext().complete();
        } finally { allowReply.countDown(); }
        assertThat(saved.await(3, TimeUnit.SECONDS)).isTrue();
        synchronized (state) {
            assertThat(state.getTurnCount()).isEqualTo(1);
            assertThat(state.getShownCandidateIds()).containsExactly(5L);
            assertThat(state.getHistory()).hasSize(3);
        }
    }

    @Test void limits_count_detached_work_until_it_finishes() {
        var limited = new ConversationStreams(service, meters, 1, 2);
        try {
            var pending = limited.prepare(user, state, "x", new MockHttpServletRequest());
            pending.emitter().complete();
            assertThatThrownBy(() -> limited.prepare(user, state, "x", new MockHttpServletRequest()))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("429");
            User other = mock(User.class);
            when(other.getId()).thenReturn(2L);
            limited.prepare(other, state, "x", new MockHttpServletRequest());
            User third = mock(User.class);
            when(third.getId()).thenReturn(3L);
            assertThatThrownBy(() -> limited.prepare(third, state, "x", new MockHttpServletRequest()))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("429");
        } finally { limited.shutdown(); }
    }

    @Test void turn_limit_and_existing_Gemini_fallback_keep_their_behavior() throws Exception {
        for (int i = 0; i < 10; i++) state.incrementTurnCount();
        String body = finish(stream());
        assertThat(events(body)).containsExactly("reply", "done");
        assertThat(data(body, "reply")).contains("\"reply\":null", "\"turnLimitReached\":true");
        verifyNoInteractions(gemini);
        state = sessions.create("s", 1L, 10L, 100L, null, null);
        toolTurn();
        when(gemini.sendFunctionResult(any(), any(), any(), any(), any())).thenReturn(new GeminiChatClient.ChatResult(null, null));
        body = finish(stream());
        assertThat(events(body)).containsExactly("cards", "reply", "done");
        assertThat(data(body, "reply")).contains("다시 시도");
        assertThat(state.getShownCandidateIds()).containsExactly(5L);
        assertThat(state.getHistory()).hasSize(3);
    }

    @Test void mock_local_timing_separates_card_arrival_from_700ms_second_reply() throws Exception {
        toolTurn();
        when(gemini.sendFunctionResult(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(700);
            return new GeminiChatClient.ChatResult(null, "찾았어요");
        });
        finish(stream());
        double cardsMs = meters.get("conversation.stream.cards").timer().totalTime(TimeUnit.MILLISECONDS);
        double completeMs = meters.get("conversation.stream.complete").tag("outcome", "success").timer().totalTime(TimeUnit.MILLISECONDS);
        assertThat(completeMs - cardsMs).isGreaterThanOrEqualTo(650);
        System.out.printf("T44 로컬 가짜 Gemini: cards=%.1fms, complete=%.1fms%n", cardsMs, completeMs);
    }

    private List<String> events(String body) {
        return body.lines().filter(line -> line.startsWith("event:")).map(line -> line.substring(6)).toList();
    }
    private String data(String body, String event) {
        return body.substring(body.indexOf("event:" + event)).lines().filter(line -> line.startsWith("data:"))
                .findFirst().orElseThrow().substring(5);
    }
}
