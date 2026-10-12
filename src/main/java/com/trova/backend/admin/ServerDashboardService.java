package com.trova.backend.admin;

import com.trova.backend.entity.ServerMetricSnapshot;
import com.trova.backend.repository.ServerMetricSnapshotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** 서버 상태 탭 — 이 서버(app.instance-name)가 남긴 스냅샷만. 같은 DB를 쓰는 다른 서버 값은 섞지 않는다. */
@Service
public class ServerDashboardService {

    public record SnapshotView(LocalDateTime measuredAt, int requestCount, Integer p50Ms, Integer p90Ms,
                               int serverErrorCount, int heapUsedMb, Integer dbActiveConnections) {
    }

    public record ServerDashboard(int days, String instanceName, List<SnapshotView> snapshots) {
    }

    private final ServerMetricSnapshotRepository repository;
    private final String instanceName;

    public ServerDashboardService(ServerMetricSnapshotRepository repository,
                                  @Value("${app.instance-name:local}") String instanceName) {
        this.repository = repository;
        this.instanceName = instanceName;
    }

    @Transactional(readOnly = true)
    public ServerDashboard server(int days) {
        DashboardPeriod period = DashboardPeriod.of(days);
        List<SnapshotView> views = repository
                .findByInstanceNameAndMeasuredAtGreaterThanEqualOrderByMeasuredAt(instanceName, period.since())
                .stream()
                .map(s -> new SnapshotView(s.getMeasuredAt(), s.getRequestCount(), s.getP50Ms(), s.getP90Ms(),
                        s.getServerErrorCount(), s.getHeapUsedMb(), s.getDbActiveConnections()))
                .toList();
        return new ServerDashboard(days, instanceName, views);
    }

    /** 요약 카드용 — since 이후 스냅샷의 p90 중 가장 큰 값. 스냅샷이 없으면 null. */
    @Transactional(readOnly = true)
    public Long maxP90Since(LocalDateTime since) {
        return repository.findByInstanceNameAndMeasuredAtGreaterThanEqualOrderByMeasuredAt(instanceName, since).stream()
                .map(ServerMetricSnapshot::getP90Ms)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .boxed()
                .max(Long::compare)
                .orElse(null);
    }
}
