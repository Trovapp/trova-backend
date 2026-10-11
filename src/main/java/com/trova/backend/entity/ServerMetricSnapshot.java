package com.trova.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 서버 상태 5분 스냅샷(#161). 운영·로컬 서버가 같은 DB를 쓸 수 있어 서버 이름(instanceName)을 함께 남긴다. 30일 보관. */
@Entity
@Table(name = "server_metric_snapshots", indexes = @Index(name = "idx_server_metric_instance_time", columnList = "instance_name, measured_at"))
public class ServerMetricSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_name", nullable = false, length = 40)
    private String instanceName;

    @Column(name = "measured_at", nullable = false)
    private LocalDateTime measuredAt;

    @Column(name = "request_count", nullable = false)
    private int requestCount;

    @Column(name = "p50_ms")
    private Integer p50Ms;

    @Column(name = "p90_ms")
    private Integer p90Ms;

    @Column(name = "server_error_count", nullable = false)
    private int serverErrorCount;

    @Column(name = "heap_used_mb", nullable = false)
    private int heapUsedMb;

    @Column(name = "db_active_connections")
    private Integer dbActiveConnections;

    protected ServerMetricSnapshot() {
    }

    public ServerMetricSnapshot(String instanceName, LocalDateTime measuredAt, int requestCount, Integer p50Ms,
                                Integer p90Ms, int serverErrorCount, int heapUsedMb, Integer dbActiveConnections) {
        this.instanceName = instanceName;
        this.measuredAt = measuredAt;
        this.requestCount = requestCount;
        this.p50Ms = p50Ms;
        this.p90Ms = p90Ms;
        this.serverErrorCount = serverErrorCount;
        this.heapUsedMb = heapUsedMb;
        this.dbActiveConnections = dbActiveConnections;
    }

    public Long getId() { return id; }
    public String getInstanceName() { return instanceName; }
    public LocalDateTime getMeasuredAt() { return measuredAt; }
    public int getRequestCount() { return requestCount; }
    public Integer getP50Ms() { return p50Ms; }
    public Integer getP90Ms() { return p90Ms; }
    public int getServerErrorCount() { return serverErrorCount; }
    public int getHeapUsedMb() { return heapUsedMb; }
    public Integer getDbActiveConnections() { return dbActiveConnections; }
}
