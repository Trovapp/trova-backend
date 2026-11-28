package com.trova.backend.shipandping;

import com.sun.management.OperatingSystemMXBean;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class ServerResources implements Supplier<Map<String, Number>> {
    private final MeterRegistry registry;
    private final OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
    private double lastPause;
    private double lastTotal;
    private double lastSteal;

    ServerResources(MeterRegistry registry) {
        this.registry = registry;
        try {
            get(); // Establish counter baselines before the first minute.
        } catch (RuntimeException unavailable) {
            lastPause = Double.NaN;
            lastSteal = Double.NaN;
            lastTotal = Double.NaN;
        }
    }

    @Override public Map<String, Number> get() {
        Map<String, Number> result = new LinkedHashMap<>();
        if (os != null) {
            percent(result, "process_cpu_pct", 100 * os.getProcessCpuLoad());
            percent(result, "system_cpu_pct", 100 * os.getCpuLoad());
            long total = os.getTotalMemorySize();
            long free = os.getFreeMemorySize();
            if (total > 0 && free >= 0) percent(result, "mem_used_pct", 100.0 * (total - free) / total);
        }

        Collection<Timer> pauses = registry.find("jvm.gc.pause").timers();
        double pause = pauses.stream().mapToDouble(t -> t.totalTime(TimeUnit.MILLISECONDS)).sum();
        if (!pauses.isEmpty() && pause >= lastPause) result.put("gc_pause_ms", pause - lastPause);
        lastPause = pause;

        Collection<FunctionCounter> counters = registry.find("host.cpu.time").functionCounters();
        double total = counters.stream().mapToDouble(FunctionCounter::count).sum();
        double steal = counters.stream().filter(c -> "steal".equals(c.getId().getTag("mode")))
                .mapToDouble(FunctionCounter::count).findFirst().orElse(Double.NaN);
        if (total > lastTotal && steal >= lastSteal) {
            percent(result, "steal_pct", 100 * (steal - lastSteal) / (total - lastTotal));
        }
        lastTotal = total;
        lastSteal = steal;
        count(result, "busy_threads", "tomcat.threads.busy");
        count(result, "db_pool_in_use", "hikaricp.connections.active");
        count(result, "db_pool_max", "hikaricp.connections.max");
        return result;
    }

    private void count(Map<String, Number> result, String field, String meter) {
        Collection<Gauge> gauges = registry.find(meter).gauges();
        if (gauges.isEmpty()) return;
        long sum = 0;
        for (Gauge gauge : gauges) {
            double value = gauge.value();
            if (!Double.isFinite(value) || value < 0 || value != Math.rint(value)) return;
            sum += (long) value;
        }
        result.put(field, sum);
    }

    private static void percent(Map<String, Number> result, String field, double value) {
        if (value >= 0 && value <= 100) result.put(field, value);
    }
}
