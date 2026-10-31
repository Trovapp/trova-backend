package com.trova.backend.pipeline;

import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineProcessTerminationFailureTest {
    private final PipelineRunner runner = new PipelineRunner("unused", "unused", "test-key", mock(ApiCallLogService.class));

    @Test
    void 자식_탐색이_실패해도_부모_종료는_시도하고_미확인으로_남긴다() {
        Process process = mock(Process.class);
        when(process.descendants()).thenThrow(new SecurityException("controlled discovery denial"));
        when(process.isAlive()).thenReturn(true, false);

        boolean confirmed = runner.terminateOwnedProcesses(process, new LinkedHashMap<>(), 1L);

        assertThat(confirmed).isFalse();
        verify(process).destroyForcibly();
    }

    @Test
    void 한_자식의_종료가_거부돼도_나머지_자식은_종료를_시도한다() {
        Process process = mock(Process.class);
        ProcessHandle denied = mock(ProcessHandle.class);
        ProcessHandle other = mock(ProcessHandle.class);
        when(process.descendants()).thenReturn(Stream.empty());
        when(process.isAlive()).thenReturn(true, false);
        when(denied.isAlive()).thenReturn(true, false);
        when(other.isAlive()).thenReturn(true, false);
        when(denied.onExit()).thenReturn(CompletableFuture.completedFuture(denied));
        when(other.onExit()).thenReturn(CompletableFuture.completedFuture(other));
        when(denied.descendants()).thenReturn(Stream.empty());
        when(other.descendants()).thenReturn(Stream.empty());
        when(denied.destroyForcibly()).thenThrow(new SecurityException("controlled termination denial"));
        Map<Long, ProcessHandle> owned = new LinkedHashMap<>();
        owned.put(10L, denied);
        owned.put(11L, other);

        boolean confirmed = runner.terminateOwnedProcesses(process, owned, 2L);

        assertThat(confirmed).isFalse();
        verify(process).destroyForcibly();
        verify(denied).destroyForcibly();
        verify(other).destroyForcibly();
    }
}
