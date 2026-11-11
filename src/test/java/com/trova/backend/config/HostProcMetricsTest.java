package com.trova.backend.config;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * /proc 파일 형식은 운영 서버(Ubuntu 22.04, E2.1.Micro)에서 2026-11-11에 읽은 실제 내용 그대로다.
 */
class HostProcMetricsTest {

    @TempDir
    Path proc;

    private void write(String file, String text) throws IOException {
        Path path = proc.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    private void writeProdLike() throws IOException {
        write("meminfo", """
                MemTotal:         979596 kB
                MemFree:           81234 kB
                MemAvailable:     209960 kB
                SwapTotal:       2097148 kB
                SwapFree:        1988348 kB
                """);
        write("vmstat", "pgpgin 1\npswpin 30502572\npswpout 20014418\n");
        write("stat", "cpu  2304600 125180 2061551 704568739 2364469 0 90927 8120511 0 0\ncpu0 1 2 3 4 5 6 7 8 0 0\n");
        write("pressure/memory", "some avg10=0.90 avg60=0.16 avg300=0.03 total=18424189605\nfull avg10=0.72 avg60=0.13 avg300=0.02 total=11264635445\n");
        write("pressure/cpu", "some avg10=0.90 avg60=0.16 avg300=0.03 total=28821559753\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=0\n");
        write("self/status", "Name:\tjava\nVmRSS:\t  512000 kB\nVmSwap:\t    1024 kB\n");
    }

    @Test
    void 서버_메모리와_스왑을_바이트로_낸다() throws IOException {
        writeProdLike();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new HostProcMetrics(proc).bindTo(registry);

        assertThat(registry.get("host.memory.total").gauge().value()).isEqualTo(979596 * 1024.0);
        assertThat(registry.get("host.memory.available").gauge().value()).isEqualTo(209960 * 1024.0);
        assertThat(registry.get("host.swap.used").gauge().value()).isEqualTo((2097148 - 1988348) * 1024.0);
        assertThat(registry.get("host.process.memory.rss").gauge().value()).isEqualTo(512000 * 1024.0);
        assertThat(registry.get("host.process.memory.swap").gauge().value()).isEqualTo(1024 * 1024.0);
        assertThat(registry.get("host.swap.pages").tag("direction", "in").functionCounter().count()).isEqualTo(30502572);
    }

    @Test
    void PSI_avg10은_퍼센트_누적_멈춤은_초로_낸다() throws IOException {
        writeProdLike();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new HostProcMetrics(proc).bindTo(registry);

        assertThat(registry.get("host.pressure.avg10").tags("resource", "memory", "type", "full").gauge().value()).isEqualTo(0.72);
        assertThat(registry.get("host.pressure.avg10").tags("resource", "cpu", "type", "some").gauge().value()).isEqualTo(0.90);
        FunctionCounter stall = registry.get("host.pressure.stall").tags("resource", "memory", "type", "full").functionCounter();
        assertThat(stall.count()).isCloseTo(11264.635445, within(1e-6));
    }

    @Test
    void CPU_steal은_cpu_합계_줄의_8번째_틱을_초로_바꾼다() throws IOException {
        writeProdLike();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new HostProcMetrics(proc).bindTo(registry);

        assertThat(registry.get("host.cpu.time").tag("mode", "steal").functionCounter().count()).isEqualTo(81205.11);
        assertThat(registry.get("host.cpu.time").tag("mode", "idle").functionCounter().count()).isEqualTo(7045687.39);
    }

    @Test
    void 프로메테우스_이름은_대시보드_쿼리에_쓸_이름과_같다() throws IOException {
        writeProdLike();
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new HostProcMetrics(proc).bindTo(registry);

        String scrape = registry.scrape();
        assertThat(scrape).contains("host_memory_available_bytes ", "host_swap_used_bytes ",
                "host_pressure_avg10_percent{resource=\"memory\",type=\"full\"}",
                "host_pressure_stall_seconds_total{resource=\"memory\",type=\"full\"}",
                "host_cpu_time_seconds_total{mode=\"steal\"}", "host_swap_pages_total{direction=\"in\"}",
                "host_process_memory_rss_bytes ");
    }

    @Test
    void meminfo가_없으면_아무것도_등록하지_않는다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new HostProcMetrics(proc).bindTo(registry);

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void 파일이_깨지거나_사라지면_NaN이고_예외를_던지지_않는다() throws IOException {
        write("meminfo", "MemTotal: abc kB\n");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new HostProcMetrics(proc).bindTo(registry);

        assertThat(registry.get("host.memory.total").gauge().value()).isNaN();
        assertThat(registry.get("host.memory.available").gauge().value()).isNaN();
        assertThat(registry.get("host.cpu.time").tag("mode", "steal").functionCounter().count()).isNaN();
        assertThat(registry.find("host.pressure.avg10").gauges()).as("pressure 파일이 없으면 PSI는 등록 안 함").isEmpty();
    }
}
