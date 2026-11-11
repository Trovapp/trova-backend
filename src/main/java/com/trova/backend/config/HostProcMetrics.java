package com.trova.backend.config;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * 서버(리눅스) 메모리·스왑·PSI·CPU steal과 java 프로세스 RSS·스왑을 /proc에서 읽어 지표로 낸다(#378, ADR 0042).
 * 2026-11 운영 부하 측정에서 조회가 무너지기 전에 먼저 나빠진 신호가 이것들이었는데, Grafana에는 없었다(alloy 때도 수집 안 함).
 * 수집기(alloy·node_exporter)를 띄우지 않고 이미 나가는 지표 전송(ADR 0039)에 싣는다 — 956MiB 서버에서 프로세스 하나가 수십 MiB다.
 *
 * - /proc/meminfo가 없으면(맥 로컬 등) 아무것도 등록하지 않는다.
 * - 값은 지표를 읽을 때(OTLP 1분, /actuator/prometheus 요청)마다 파일을 다시 읽되, 같은 파일은 1초 안에 한 번만 읽는다.
 * - 읽기에 실패하면 NaN(그래프에서 빈 칸)이고 예외를 밖으로 던지지 않는다.
 * - CPU 시간은 /proc/stat의 틱을 USER_HZ=100으로 나눠 초로 바꾼다(운영 getconf CLK_TCK=100 확인).
 */
@Component
public class HostProcMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(HostProcMetrics.class);
    static final double USER_HZ = 100.0;
    static final long CACHE_NANOS = 1_000_000_000L;
    // /proc/stat cpu 줄 순서: user nice system idle iowait irq softirq steal
    static final List<String> CPU_MODES = List.of("user", "nice", "system", "idle", "iowait", "irq", "softirq", "steal");

    private final Path proc;
    private final Map<String, Cached> cache = new HashMap<>();

    public HostProcMetrics() {
        this(Path.of("/proc"));
    }

    HostProcMetrics(Path proc) {
        this.proc = proc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        if (!Files.isReadable(proc.resolve("meminfo"))) {
            log.info("서버 지표 건너뜀: {}/meminfo 없음", proc);
            return;
        }
        gauge(registry, "host.memory.total", "서버 전체 메모리", "bytes", m -> kib("meminfo", "MemTotal"));
        gauge(registry, "host.memory.available", "서버에서 새로 쓸 수 있는 메모리(MemAvailable)", "bytes", m -> kib("meminfo", "MemAvailable"));
        gauge(registry, "host.swap.total", "서버 스왑 크기", "bytes", m -> kib("meminfo", "SwapTotal"));
        gauge(registry, "host.swap.used", "서버 스왑 사용량", "bytes", m -> kib("meminfo", "SwapTotal") - kib("meminfo", "SwapFree"));

        for (String direction : List.of("in", "out")) {
            FunctionCounter.builder("host.swap.pages", this, m -> m.value("vmstat", "pswp" + direction))
                    .description("스왑으로 읽고(in) 쓴(out) 페이지 수(4KiB)")
                    .tag("direction", direction)
                    .register(registry);
        }

        for (String resource : List.of("memory", "cpu")) {
            if (!Files.isReadable(proc.resolve("pressure/" + resource))) {
                continue;
            }
            for (String type : List.of("some", "full")) {
                Gauge.builder("host.pressure.avg10", this, m -> m.pressure(resource, type, "avg10"))
                        .description("PSI: 최근 10초 동안 " + resource + "를 기다리며 멈춘 시간 비율(%)")
                        .baseUnit("percent")
                        .tags("resource", resource, "type", type)
                        .register(registry);
                FunctionCounter.builder("host.pressure.stall", this, m -> m.pressure(resource, type, "total") / 1_000_000.0)
                        .description("PSI: " + resource + "를 기다리며 멈춘 누적 시간")
                        .baseUnit("seconds")
                        .tags("resource", resource, "type", type)
                        .register(registry);
            }
        }

        for (int i = 0; i < CPU_MODES.size(); i++) {
            int index = i;
            FunctionCounter.builder("host.cpu.time", this, m -> m.cpuTicks(index) / USER_HZ)
                    .description("서버 전체 CPU 시간(모든 코어 합). steal은 무료 서버에서 다른 사용자에게 빼앗긴 시간")
                    .baseUnit("seconds")
                    .tag("mode", CPU_MODES.get(i))
                    .register(registry);
        }

        gauge(registry, "host.process.memory.rss", "java 프로세스가 실제 메모리에 올린 크기(VmRSS)", "bytes", m -> kib("self/status", "VmRSS"));
        gauge(registry, "host.process.memory.swap", "java 프로세스가 스왑에 밀린 크기(VmSwap)", "bytes", m -> kib("self/status", "VmSwap"));
    }

    private void gauge(MeterRegistry registry, String name, String description, String unit, ToDoubleFunction<HostProcMetrics> f) {
        Gauge.builder(name, this, f).description(description).baseUnit(unit).register(registry);
    }

    /** "Key:   123 kB" 줄의 값을 바이트로. */
    private double kib(String file, String key) {
        return value(file, key) * 1024;
    }

    /** "key value" 또는 "Key: value kB" 줄의 첫 숫자. */
    double value(String file, String key) {
        String text = read(file);
        if (text == null) {
            return Double.NaN;
        }
        for (String line : text.split("\n")) {
            String[] parts = line.trim().split("[:\\s]+");
            if (parts.length >= 2 && parts[0].equals(key)) {
                try {
                    return Double.parseDouble(parts[1]);
                } catch (NumberFormatException e) {
                    return Double.NaN;
                }
            }
        }
        return Double.NaN;
    }

    /** /proc/pressure/<resource>의 "some avg10=0.90 avg60=... total=123" 줄에서 field 값. */
    double pressure(String resource, String type, String field) {
        String text = read("pressure/" + resource);
        if (text == null) {
            return Double.NaN;
        }
        for (String line : text.split("\n")) {
            if (!line.startsWith(type + " ")) {
                continue;
            }
            for (String part : line.split("\\s+")) {
                if (part.startsWith(field + "=")) {
                    try {
                        return Double.parseDouble(part.substring(field.length() + 1));
                    } catch (NumberFormatException e) {
                        return Double.NaN;
                    }
                }
            }
        }
        return Double.NaN;
    }

    /** /proc/stat 첫 줄(cpu 합계)의 index번째 틱. */
    double cpuTicks(int index) {
        String text = read("stat");
        if (text == null) {
            return Double.NaN;
        }
        String first = text.split("\n", 2)[0];
        String[] parts = first.trim().split("\\s+");
        if (!parts[0].equals("cpu") || parts.length <= index + 1) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(parts[index + 1]);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private synchronized String read(String file) {
        long now = System.nanoTime();
        Cached cached = cache.get(file);
        if (cached != null && now - cached.at < CACHE_NANOS) {
            return cached.text;
        }
        String text;
        try {
            text = Files.readString(proc.resolve(file));
        } catch (IOException | RuntimeException e) {
            text = null;
        }
        cache.put(file, new Cached(now, text));
        return text;
    }

    private record Cached(long at, String text) {
    }
}
