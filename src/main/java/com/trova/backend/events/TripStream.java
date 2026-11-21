package com.trova.backend.events;

import jakarta.servlet.AsyncContext;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** One bounded FIFO and one writer for the lifetime of an admitted connection. */
final class TripStream implements Runnable {
    private static final long MAX_WRITE_NANOS = Duration.ofSeconds(25).toNanos();
    final long trip;
    final long user;
    final SseEmitter emitter;
    private final AtomicLong latestOffered;
    private long previousHeartbeatVersion;
    private final StreamAuth auth;
    private final Clock clock;
    private final ArrayBlockingQueue<SseEmitter.SseEventBuilder> pending = new ArrayBlockingQueue<>(8);
    private final CountDownLatch ready = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Executor sender;
    private final Consumer<TripStream> release;
    private volatile Thread writer;
    private volatile AsyncContext transport;
    private volatile long writeStarted;

    TripStream(long trip, long user, long version, StreamAuth auth, Clock clock, long timeout,
               Executor sender, Consumer<TripStream> release) {
        this.trip = trip;
        this.latestOffered = new AtomicLong(version);
        this.previousHeartbeatVersion = version;
        this.user = user;
        this.auth = auth;
        this.clock = clock;
        this.emitter = new SseEmitter(timeout);
        this.sender = sender;
        this.release = release;
        emitter.onCompletion(this::close);
        emitter.onError(error -> close());
        emitter.onTimeout(this::close);
    }

    @Override
    public void run() {
        writer = Thread.currentThread();
        try {
            if (closed.get() || !ready.await(10, TimeUnit.SECONDS)) return;
            while (active()) {
                var message = pending.take();
                writeStarted = System.nanoTime();
                if (!active()) break;
                emitter.send(message);
                writeStarted = 0;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalStateException disconnected) {
            // The servlet container may already have closed the response.
        } finally {
            closed.set(true);
            pending.clear();
            try {
                emitter.complete();
            } catch (IllegalStateException alreadyCompleted) {
                // A client disconnect can recycle Tomcat's response before the writer exits.
            } finally {
                release.accept(this);
            }
        }
    }

    // Spring has installed the response handler. Early writes would otherwise be buffered and
    // flushed by the servlet request thread while it initializes the emitter.
    void ready(AsyncContext context) {
        transport = context;
        ready.countDown();
    }

    boolean active() { return !closed.get() && auth.validAt(clock.instant()); }

    void changed(TripChangedEvent event) {
        offer(SseEmitter.event().name("changed").data(event));
        latestOffered.accumulateAndGet(event.version(), Math::max);
    }

    // Allow one heartbeat interval for normal delivery. A persistent gap means this
    // subscription needs the same refetch the client already performs on reconnect.
    synchronized void heartbeat(long committedVersion) {
        if (latestOffered.get() < previousHeartbeatVersion) close();
        else offer(SseEmitter.event().comment("heartbeat"));
        previousHeartbeatVersion = committedVersion;
    }

    void offer(SseEmitter.SseEventBuilder message) {
        long started = writeStarted;
        if (!active() || (started != 0 && System.nanoTime() - started >= MAX_WRITE_NANOS) || !pending.offer(message))
            close();
    }

    void close() {
        if (!closed.compareAndSet(false, true)) return;
        Thread task = writer;
        if (task != null) task.interrupt();
        AsyncContext context = transport;
        if (writeStarted != 0 && context != null) {
            // complete() on the emitter waits for its write lock. Ask the container independently
            // to finish a stalled request and retain the admission slot until the writer exits.
            sender.execute(() -> {
                try { context.complete(); }
                catch (IllegalStateException alreadyCompleted) { /* Servlet has finished. */ }
            });
        }
    }
}
