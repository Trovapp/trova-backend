package com.trova.backend.service;

import com.trova.backend.entity.ApiCallLog;
import com.trova.backend.repository.ApiCallLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ApiCallLogServiceTest {

    @Mock
    private ApiCallLogRepository apiCallLogRepository;

    @InjectMocks
    private ApiCallLogService apiCallLogService;

    @Test
    void 마커_줄_하나를_파싱해서_저장한다() {
        String stderr = """
                [extract_places] 진행 중...
                TROVA_API_LOG:{"provider":"gemini","operation":"extract_places.collect","latencyMs":842,"success":true,"promptTokens":1523,"responseTokens":89,"totalTokens":1612}
                """;

        apiCallLogService.recordFromStderr(stderr, 15L);

        ArgumentCaptor<ApiCallLog> captor = ArgumentCaptor.forClass(ApiCallLog.class);
        verify(apiCallLogRepository, times(1)).save(captor.capture());
        ApiCallLog saved = captor.getValue();
        assertThat(saved.getProvider()).isEqualTo("gemini");
        assertThat(saved.getOperation()).isEqualTo("extract_places.collect");
        assertThat(saved.getJobId()).isEqualTo(15L);
        assertThat(saved.getLatencyMs()).isEqualTo(842);
        assertThat(saved.isSuccess()).isTrue();
        assertThat(saved.getPromptTokens()).isEqualTo(1523);
        assertThat(saved.getResponseTokens()).isEqualTo(89);
        assertThat(saved.getTotalTokens()).isEqualTo(1612);
    }

    @Test
    void 마커_줄이_여러개면_각각_저장한다() {
        String stderr = """
                TROVA_API_LOG:{"provider":"gemini","operation":"extract_places.collect","latencyMs":800,"success":true}
                TROVA_API_LOG:{"provider":"gemini","operation":"extract_places.filter","latencyMs":650,"success":true}
                """;

        apiCallLogService.recordFromStderr(stderr, 15L);

        verify(apiCallLogRepository, times(2)).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 마커_줄이_없으면_아무것도_저장하지_않는다() {
        String stderr = "[run_pipeline] 다운로드 중...\n[run_pipeline] 완료\n";

        apiCallLogService.recordFromStderr(stderr, 15L);

        verify(apiCallLogRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 마커_줄의_JSON이_깨졌으면_그_줄만_건너뛰고_나머지는_저장한다() {
        String stderr = """
                TROVA_API_LOG:{이건 JSON이 아님}
                TROVA_API_LOG:{"provider":"gemini","operation":"verify_places","latencyMs":500,"success":true}
                """;

        apiCallLogService.recordFromStderr(stderr, 15L);

        verify(apiCallLogRepository, times(1)).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 토큰_필드가_없으면_null로_저장한다_카카오_등() {
        String stderr = "TROVA_API_LOG:{\"provider\":\"kakao\",\"operation\":\"keyword_search\",\"latencyMs\":120,\"success\":true}\n";

        apiCallLogService.recordFromStderr(stderr, 15L);

        ArgumentCaptor<ApiCallLog> captor = ArgumentCaptor.forClass(ApiCallLog.class);
        verify(apiCallLogRepository).save(captor.capture());
        assertThat(captor.getValue().getPromptTokens()).isNull();
        assertThat(captor.getValue().getResponseTokens()).isNull();
        assertThat(captor.getValue().getTotalTokens()).isNull();
    }

    @Test
    void 실패_케이스는_에러메시지와_함께_저장한다() {
        String stderr = "TROVA_API_LOG:{\"provider\":\"gemini\",\"operation\":\"select_place_match\",\"latencyMs\":30000,\"success\":false,\"errorMessage\":\"Gemini API error 500\"}\n";

        apiCallLogService.recordFromStderr(stderr, 15L);

        ArgumentCaptor<ApiCallLog> captor = ArgumentCaptor.forClass(ApiCallLog.class);
        verify(apiCallLogRepository).save(captor.capture());
        assertThat(captor.getValue().isSuccess()).isFalse();
        assertThat(captor.getValue().getErrorMessage()).isEqualTo("Gemini API error 500");
    }

    @Test
    void record으로_직접_기록할_수도_있다() {
        apiCallLogService.record("kakao", "keyword_search", 15L, 120, true, null, null, null, null);

        verify(apiCallLogRepository, times(1)).save(org.mockito.ArgumentMatchers.any());
    }
}
