package com.trova.backend.admin;

import com.trova.backend.entity.ServerMetricSnapshot;
import com.trova.backend.repository.ServerMetricSnapshotRepository;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 5분마다 서버 상태 한 줄을 남기고, 하루 한 번 30일 지난 줄을 지운다(#161). */
@Service
public class ServerMetricSnapshotRecorder {

    private final RequestTimingFilter timingFilter;
    private final ServerMetricSnapshotRepository repository;
    private final DataSource dataSource;
    private final String instanceName;

    public ServerMetricSnapshotRecorder(RequestTimingFilter timingFilter, ServerMetricSnapshotRepository repository,
                                        DataSource dataSource, @Value("${app.instance-name:local}") String instanceName) {
        this.timingFilter = timingFilter;
        this.repository = repository;
        this.dataSource = dataSource;
        this.instanceName = instanceName;
    }

    @Scheduled(fixedRate = 5, initialDelay = 5, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void record() {
        List<RequestTimingFilter.Sample> samples = timingFilter.drain();
        List<Long> durations = samples.stream().map(RequestTimingFilter.Sample::durationMs).toList();
        Long p50 = Percentiles.of(durations, 0.5);
        Long p90 = Percentiles.of(durations, 0.9);
        int errors = (int) samples.stream().filter(s -> s.status() >= 500).count();
        Runtime rt = Runtime.getRuntime();
        int heapMb = (int) ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        repository.save(new ServerMetricSnapshot(instanceName, LocalDateTime.now(), samples.size(),
                p50 == null ? null : p50.intValue(), p90 == null ? null : p90.intValue(), errors, heapMb, activeConnections()));
    }

    @Scheduled(cron = "0 30 4 * * *")
    @Transactional
    public void purgeOld() {
        repository.deleteOlderThan(LocalDateTime.now().minusDays(30));
    }

    private Integer activeConnections() {
        if (dataSource instanceof HikariDataSource hikari) {
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            return pool == null ? null : pool.getActiveConnections();
        }
        return null;
    }
}
