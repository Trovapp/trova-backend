package com.trova.backend.shipandping;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.hibernate.SessionEventListener;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

public final class RequestIoTime implements SessionEventListener {
    static final String ATTRIBUTE = RequestIoTime.class.getName();
    private long start;
    private Totals totals;

    static final class Totals {
        private long jdbcNanos = -1;
        private long externalMillis = -1;

        synchronized void db(long nanos) {
            jdbcNanos = Math.min(TimeUnit.MINUTES.toNanos(10), Math.max(0, jdbcNanos) + nanos);
        }

        synchronized void external(long ms) {
            externalMillis = Math.min(600000, Math.max(0, externalMillis) + Math.min(ms, 600000));
        }

        synchronized Long dbMillis() {
            return jdbcNanos < 0 ? null : TimeUnit.NANOSECONDS.toMillis(jdbcNanos);
        }

        synchronized Long externalMillis() {
            return externalMillis < 0 ? null : externalMillis;
        }
    }

    private static Totals current() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) return null;
        try {
            return (Totals) attrs.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        } catch (IllegalStateException ignored) {
            return null; // MVC already released the request.
        }
    }

    public static void recordExternal(String provider, long millis) {
        if (millis < 0 || "internal".equals(provider) || "google-places".equals(provider)) return;
        Totals current = current();
        if (current != null) current.external(millis);
    }

    public static <T> T googlePlacesCall(Supplier<T> call) {
        Totals totals = current();
        if (totals == null) return call.get();
        long start = System.nanoTime();
        try {
            return call.get();
        } finally {
            totals.external(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    public static RequestIoTime startDirectJdbc() {
        RequestIoTime timer = new RequestIoTime();
        timer.jdbcExecuteStatementStart();
        return timer;
    }

    @Override public void jdbcExecuteStatementStart() {
        totals = current();
        if (totals != null) start = System.nanoTime();
    }

    @Override public void jdbcExecuteStatementEnd() {
        if (totals != null) {
            totals.db(System.nanoTime() - start);
            totals = null;
        }
    }

    @Override public void jdbcExecuteBatchStart() { jdbcExecuteStatementStart(); }
    @Override public void jdbcExecuteBatchEnd() { jdbcExecuteStatementEnd(); }
}
