package com.trova.backend.queue;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.service.PlaceExtractionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShareJobListenerTest {

    private final ProcessingJobRepository repository = mock(ProcessingJobRepository.class);
    private final PlaceExtractionService extraction = mock(PlaceExtractionService.class);
    private final ShareJobListener listener = new ShareJobListener(repository, extraction);

    private void jobWithStatus(JobStatus status) {
        ProcessingJob job = mock(ProcessingJob.class);
        when(job.getStatus()).thenReturn(status);
        when(repository.findById(42L)).thenReturn(Optional.of(job));
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"PENDING", "PROCESSING"})
    void 끝나지_않은_작업은_이_스레드에서_분석한다(JobStatus status) {
        jobWithStatus(status);

        listener.onMessage("42");

        verify(extraction).processNow(42L);
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"DONE", "FAILED"})
    void 다시_받은_메시지가_끝난_작업이면_건너뛴다(JobStatus status) {
        jobWithStatus(status);

        listener.onMessage("42");

        verify(extraction, never()).processNow(anyLong());
    }

    @Test
    void 지워진_작업이면_건너뛴다() {
        when(repository.findById(42L)).thenReturn(Optional.empty());

        listener.onMessage("42");

        verify(extraction, never()).processNow(anyLong());
    }
}
