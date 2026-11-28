package com.trova.backend.shipandping;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

final class Sender implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(Sender.class);
    private record Pending(RequestRecord record, long queuedAt) {}
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final JsonMapper json = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private final String key;
    private final URI endpoint;
    private final String version;
    private final long interval;
    private final HttpClient client;
    private final Thread worker;
    private final Duration shutdownTimeout;
    private int inFlight;
    private boolean closed;
    private final ScheduledThreadPoolExecutor clock;
    private final Set<RequestFilter.Observation> active = new HashSet<>();
    private ScheduledFuture<?> alarm;
    private long alarmAt;
    private ProfileUpload profile;
    private Supplier<Map<String, Number>> resources;
    private boolean checkProfile;
    private boolean sampleDue;

    synchronized void connect(Map<String, ?> values, Path receipt, Supplier<Map<String, Number>> sampler,
                              Duration period) {
        if (worker == null || closed || resources != null) return;
        profile = new ProfileUpload(json.writeValueAsBytes(values), key, endpoint, receipt);
        resources = sampler;
        checkProfile = true;
        clock.scheduleAtFixedRate(() -> {
            synchronized (this) {
                sampleDue = true;
                checkProfile = true;
                notifyAll();
            }
        }, period.toNanos(), period.toNanos(), TimeUnit.NANOSECONDS);
        notifyAll();
    }

    synchronized void watch(RequestFilter.Observation observation) {
        if (clock == null || closed) return;
        active.add(observation);
        scheduleAlarm(observation.deadlineNanos());
    }

    synchronized void forget(RequestFilter.Observation observation) {
        active.remove(observation);
    }

    private void scheduleAlarm(long at) {
        if (alarm != null && at - alarmAt >= 0) return;
        if (alarm != null) alarm.cancel(false);
        alarmAt = at;
        alarm = clock.schedule(() -> checkDeadlines(at), at - System.nanoTime(), TimeUnit.NANOSECONDS);
    }

    private void checkDeadlines(long at) {
        List<RequestFilter.Observation> due = new ArrayList<>();
        synchronized (this) {
            if (closed || alarmAt != at) return;
            alarm = null;
            long now = System.nanoTime();
            long next = Long.MAX_VALUE;
            for (RequestFilter.Observation observation : active) {
                long deadline = observation.deadlineNanos();
                if (now - deadline >= 0) due.add(observation);
                else next = Math.min(next, deadline);
            }
            if (next != Long.MAX_VALUE) scheduleAlarm(next);
        }
        // finish() also offers to the sender. Never take an observation lock under the sender lock.
        for (RequestFilter.Observation observation : due) observation.expire(System.nanoTime());
    }

    Sender(String key, URI endpoint, String version, Duration interval) {
        this(key, endpoint, version, interval, Duration.ofSeconds(25));
    }

    Sender(String key, URI endpoint, String version, Duration interval, Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
        this.key = key;
        this.endpoint = endpoint;
        this.version = version;
        this.interval = interval.toNanos();
        boolean enabled = key != null && !key.isBlank();
        client = enabled ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build() : null;
        clock = enabled ? new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name("ship-and-ping-clock").factory()) : null;
        if (clock != null) clock.setRemoveOnCancelPolicy(true);
        worker = enabled ? Thread.ofPlatform().daemon().name("ship-and-ping").start(this::run) : null;
    }

    synchronized void offer(RequestRecord record) {
        if (worker == null || closed || queue.size() >= 1000) return;
        queue.addLast(new Pending(record, System.nanoTime()));
        if (queue.size() == 1 || queue.size() == 50) notifyAll();
    }

    private record Batch(List<RequestRecord> records, boolean sample, boolean profile) {}

    private synchronized Batch nextBatch() throws InterruptedException {
        while (!closed && !sampleDue && !checkProfile) {
            if (queue.isEmpty()) {
                wait();
            } else {
                long remaining = queue.getFirst().queuedAt() + interval - System.nanoTime();
                if (queue.size() >= 50 || remaining <= 0) break;
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }
        if (closed && queue.isEmpty()) return null;
        List<RequestRecord> records = new ArrayList<>();
        while (!queue.isEmpty() && records.size() < 100) records.add(queue.removeFirst().record());
        inFlight = records.size();
        Batch batch = new Batch(records, sampleDue, checkProfile);
        sampleDue = false;
        checkProfile = false;
        return batch;
    }

    private void run() {
        try {
            while (true) {
                Batch batch = nextBatch();
                if (batch == null) return;
                if (batch.profile()) uploadProfile();
                try {
                    if (batch.sample() || !batch.records().isEmpty()) {
                        send(batch.records(), batch.sample() ? sample() : null);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // 수집 장애 때 버린다. 요청 경로나 키가 들어갈 수 있는 예외는 로그에 남기지 않는다.
                } finally {
                    synchronized (this) {
                        inFlight = 0;
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void uploadProfile() throws InterruptedException {
        try {
            if (profile.pending()) {
                int status = post(endpoint.resolve("/v1/profile"), profile.body);
                if (status >= 200 && status < 300) profile.acknowledge();
            }
        } catch (IOException | RuntimeException ignored) {
            // Retry a failed upload on the next minute without delaying request handling.
        }
    }

    private Map<String, Number> sample() {
        try {
            return resources.get();
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private void send(List<RequestRecord> records, Map<String, Number> sample) throws IOException, InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        Map<String, Object> envelope = new LinkedHashMap<>(Map.of("spec", "sp-1", "platform", "server",
                "batch_id", UUID.randomUUID().toString(), "sent_at", Instant.now().toString(),
                "app_version", version, "records", records));
        if (sample != null) envelope.put("resources", sample);
        byte[] body = json.writeValueAsBytes(envelope);
        if (body.length > 65536) {
            int middle = records.size() / 2;
            if (middle > 0) {
                send(records.subList(0, middle), sample);
                send(records.subList(middle, records.size()), null);
            } else if (sample != null && !records.isEmpty()) {
                send(List.of(), sample);
                send(records, null);
            } else {
                synchronized (this) {
                    inFlight--;
                }
            }
            return;
        }
        int status = post(endpoint, body);
        synchronized (this) {
            inFlight -= records.size();
        }
        LOG.debug("쉽앤핑 요청 묶음 전송 HTTP {}", status);
    }

    private int post(URI target, byte[] body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(2))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Override public void close() {
        if (worker == null) return;
        synchronized (this) {
            if (closed) return;
            closed = true;
            clock.shutdownNow();
            active.clear();
            notifyAll();
        }
        try {
            worker.join(shutdownTimeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                int discarded = queue.size() + inFlight;
                queue.clear();
                if (discarded > 0) LOG.warn("쉽앤핑 종료 시 전송 미확인 {}건 폐기", discarded);
                if (worker.isAlive()) worker.interrupt();
            }
            client.shutdownNow();
        }
    }
}
