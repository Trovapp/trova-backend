package com.trova.backend.admin;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.service.OrphanedJobRecoveryService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FailureReasonTest {

    @Test
    void 알려진_실패_문구는_이유별로_나눈다() {
        assertThat(FailureReason.classify(ProcessingJob.NO_PLACES_MESSAGE)).isEqualTo(FailureReason.NO_PLACES);
        assertThat(FailureReason.classify(ProcessingJob.AI_QUOTA_MESSAGE)).isEqualTo(FailureReason.AI_QUOTA);
        assertThat(FailureReason.classify(ProcessingJob.SOURCE_RATE_LIMIT_MESSAGE)).isEqualTo(FailureReason.SOURCE_RATE_LIMITED);
        assertThat(FailureReason.classify(OrphanedJobRecoveryService.STALE_MESSAGE)).isEqualTo(FailureReason.STALE);
        assertThat(FailureReason.classify(ProcessingJob.NO_MEDIA_MESSAGE)).isEqualTo(FailureReason.NO_MEDIA);
        assertThat(FailureReason.classify(ProcessingJob.NOT_PLACE_CONTENT_MESSAGE)).isEqualTo(FailureReason.NOT_PLACE_CONTENT);
    }

    @Test
    void 모르는_문구나_비어_있으면_기타다() {
        assertThat(FailureReason.classify("yt-dlp 다운로드 실패")).isEqualTo(FailureReason.OTHER);
        assertThat(FailureReason.classify(null)).isEqualTo(FailureReason.OTHER);
        assertThat(FailureReason.classify(" ")).isEqualTo(FailureReason.OTHER);
    }
}
