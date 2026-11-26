package com.trova.backend.shipandping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

final class Sender implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(Sender.class);
    private record Pending(RequestRecord record, long queuedAt) {}
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final JsonMapper json = JsonMapper.builder().build();
    private final String key;
    private final URI endpoint;
    private final String version;
    private final long interval;
    private final HttpClient client;
    private final Thread worker;
    private final Duration shutdownTimeout;
    private int inFlight;
    private boolean closed;

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
        worker = enabled ? Thread.ofPlatform().daemon().name("ship-and-ping").start(this::run) : null;
    }

    synchronized void offer(RequestRecord record) {
        if (worker == null || closed || queue.size() >= 1000) return;
        queue.addLast(new Pending(record, System.nanoTime()));
        if (queue.size() == 1 || queue.size() == 50) notifyAll();
    }

    private synchronized List<RequestRecord> nextBatch() throws InterruptedException {
        while (!closed && queue.isEmpty()) wait();
        if (queue.isEmpty()) return List.of();
        long deadline = queue.getFirst().queuedAt() + interval;
        long remaining = deadline - System.nanoTime();
        while (!closed && queue.size() < 50 && remaining > 0) {
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
            remaining = deadline - System.nanoTime();
        }
        List<RequestRecord> records = new ArrayList<>();
        while (!queue.isEmpty() && records.size() < 100) records.add(queue.removeFirst().record());
        inFlight = records.size();
        return records;
    }

    private void run() {
        try {
            while (true) {
                List<RequestRecord> records = nextBatch();
                if (records.isEmpty()) return;
                try {
                    send(records);
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

    private void send(List<RequestRecord> records) throws IOException, InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        byte[] body = json.writeValueAsBytes(Map.of("spec", "sp-1", "platform", "server",
                "batch_id", UUID.randomUUID().toString(), "sent_at", Instant.now().toString(),
                "app_version", version, "records", records));
        if (body.length > 65536) {
            int middle = records.size() / 2;
            if (middle > 0) {
                send(records.subList(0, middle));
                send(records.subList(middle, records.size()));
            } else {
                synchronized (this) {
                    inFlight--;
                }
            }
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        synchronized (this) {
            inFlight -= records.size();
        }
        LOG.debug("쉽앤핑 요청 묶음 전송 HTTP {}", status);
    }

    @Override public void close() {
        if (worker == null) return;
        synchronized (this) {
            if (closed) return;
            closed = true;
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
