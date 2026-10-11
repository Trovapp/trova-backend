package com.trova.backend.admin;

import com.trova.backend.entity.ServerMetricSnapshot;
import com.trova.backend.repository.ServerMetricSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "app.instance-name=test-rec")
class ServerMetricSnapshotRecorderTest {

    @Autowired
    private ServerMetricSnapshotRecorder recorder;

    @Autowired
    private RequestTimingFilter timingFilter;

    @Autowired
    private ServerMetricSnapshotRepository repository;

    @Test
    void 모인_요청으로_5분_스냅샷을_만들고_모은_값은_비운다() {
        timingFilter.drain();
        for (long ms = 10; ms <= 100; ms += 10) {
            timingFilter.add(new RequestTimingFilter.Sample(ms, ms == 100 ? 500 : 200));
        }

        recorder.record();

        List<ServerMetricSnapshot> saved = repository.findByInstanceNameAndMeasuredAtGreaterThanEqualOrderByMeasuredAt(
                "test-rec", LocalDateTime.now().minusMinutes(1));
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getRequestCount()).isEqualTo(10);
        assertThat(saved.get(0).getP50Ms()).isEqualTo(50);
        assertThat(saved.get(0).getP90Ms()).isEqualTo(90);
        assertThat(saved.get(0).getServerErrorCount()).isEqualTo(1);
        assertThat(saved.get(0).getHeapUsedMb()).isPositive();
        assertThat(timingFilter.drain()).isEmpty();
    }

    @Test
    void 요청이_없던_5분도_요청_0으로_남긴다() {
        timingFilter.drain();
        recorder.record();
        List<ServerMetricSnapshot> saved = repository.findByInstanceNameAndMeasuredAtGreaterThanEqualOrderByMeasuredAt(
                "test-rec", LocalDateTime.now().minusMinutes(1));
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getRequestCount()).isZero();
        assertThat(saved.get(0).getP90Ms()).isNull();
    }

    @Test
    void 기록한_지_30일_지난_스냅샷은_지운다() {
        repository.save(new ServerMetricSnapshot("test-rec", LocalDateTime.now().minusDays(31), 1, 10, 10, 0, 100, 1));
        repository.save(new ServerMetricSnapshot("test-rec", LocalDateTime.now().minusDays(29), 1, 10, 10, 0, 100, 1));

        recorder.purgeOld();

        assertThat(repository.findByInstanceNameAndMeasuredAtGreaterThanEqualOrderByMeasuredAt(
                "test-rec", LocalDateTime.now().minusDays(60))).hasSize(1);
    }
}
