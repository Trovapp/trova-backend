package com.trova.backend.admin;

import com.trova.backend.entity.ServerMetricSnapshot;
import com.trova.backend.repository.ServerMetricSnapshotRepository;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServerMetricSnapshotRecorderFailureTest {

    @Test
    void 저장이_실패하면_모은_표본을_되돌려_다음_스냅샷에_넣는다() {
        // 스냅샷 시각에 DB가 잠깐 끊기면 그 5분 표본이 통째로 사라졌다 — 장애 구간 지표가 가장 필요하다(리뷰 지적, #161).
        RequestTimingFilter filter = new RequestTimingFilter();
        filter.add(new RequestTimingFilter.Sample(30, 200));
        filter.add(new RequestTimingFilter.Sample(40, 500));
        ServerMetricSnapshotRepository repository = mock(ServerMetricSnapshotRepository.class);
        when(repository.save(any(ServerMetricSnapshot.class))).thenThrow(new IllegalStateException("DB 연결 끊김"));
        ServerMetricSnapshotRecorder recorder = new ServerMetricSnapshotRecorder(filter, repository, mock(DataSource.class), "test");

        assertThatThrownBy(recorder::record).isInstanceOf(IllegalStateException.class);

        assertThat(filter.drain()).hasSize(2);
    }
}
