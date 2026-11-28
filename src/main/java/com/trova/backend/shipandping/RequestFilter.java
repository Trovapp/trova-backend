package com.trova.backend.shipandping;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.EOFException;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

final class RequestFilter extends OncePerRequestFilter {
    static final String ERROR_CLASS = RequestFilter.class.getName() + ".errorClass";
    static final String ABORTED = RequestFilter.class.getName() + ".aborted";
    static final String ACTIVE = RequestFilter.class.getName() + ".active";
    private static final Pattern VARIABLE = Pattern.compile("\\{[^/}]+}");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "TRACE");
    private final ConcurrentHashMap<String, String> templates = new ConcurrentHashMap<>();
    private final Sender sender;
    private final ApiRoutes routes = new ApiRoutes();
    private final long defaultTimeout;

    RequestFilter(Sender sender) { this(sender, 10000); }

    RequestFilter(Sender sender, long defaultTimeout) {
        this.sender = sender;
        this.defaultTimeout = defaultTimeout > 0 ? defaultTimeout : 10000;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || "OPTIONS".equals(request.getMethod());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        Observation obs = new Observation(request, response);
        request.setAttribute(ACTIVE, obs);
        request.setAttribute(RequestIoTime.ATTRIBUTE, obs.io);
        obs.arm(defaultTimeout);
        Throwable failure = null;
        try {
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public AsyncContext startAsync() {
                    return obs.listen(super.startAsync());
                }

                @Override public AsyncContext startAsync(ServletRequest req, ServletResponse res) {
                    return obs.listen(super.startAsync(req, res));
                }
            }, response);
        } catch (IOException | ServletException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            if (failure == null && request.isAsyncStarted()) {
                try {
                    AsyncContext context = request.getAsyncContext();
                    obs.listen(context);
                    if (context.getTimeout() > 0) obs.arm(context.getTimeout());
                } catch (IllegalStateException alreadyDispatched) {
                    if (!obs.listening()) obs.finish(null, null);
                }
            } else if (failure != null || (!obs.listening() && request.getAttribute(ArrivalValve.CONTAINER) == null)) {
                obs.finish(null, failure);
            }
        }
    }

    private String route(HttpServletRequest request) {
        Object mapping = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (mapping instanceof String template && template.startsWith("/api/")) {
            return templates.computeIfAbsent(template, key -> VARIABLE.matcher(key).replaceAll(":id"));
        }
        return routes.lookup(request.getRequestURI().substring(request.getContextPath().length()), request.getMethod());
    }

    static boolean disconnected(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ClientAbortException || cause instanceof EOFException
                    || cause instanceof AsyncRequestNotUsableException) return true;
        }
        return false;
    }

    final class Observation implements AsyncListener {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private long entered = System.nanoTime();
        private final Long initialQueue;
        private Long queue;
        private String handlerRoute;
        private final RequestIoTime.Totals io = new RequestIoTime.Totals();
        private volatile long deadlineNanos;
        private long timeout;
        private boolean finished;
        private AsyncContext attached;

        Observation(HttpServletRequest request, HttpServletResponse response) {
            this.request = request;
            this.response = response;
            Object received = request.getAttribute(ArrivalValve.RECEIVED);
            initialQueue = received instanceof Long start ? Math.max(0, System.currentTimeMillis() - start) : null;
            queue = initialQueue;
        }

        private long elapsed() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - entered) + (initialQueue == null ? 0 : initialQueue);
        }

        synchronized void handlerStarted() {
            if (finished) return;
            handlerRoute = route(request);
            if (initialQueue != null) {
                queue = elapsed();
            } else {
                entered = System.nanoTime();
                deadlineNanos = entered + TimeUnit.MILLISECONDS.toNanos(timeout);
                sender.watch(this);
            }
        }

        synchronized void arm(long millis) {
            if (finished || timeout == millis) return;
            timeout = millis;
            deadlineNanos = entered + TimeUnit.MILLISECONDS.toNanos(timeout - (initialQueue == null ? 0 : initialQueue));
            sender.watch(this);
        }

        long deadlineNanos() { return deadlineNanos; }

        void expire(long now) {
            if (now - deadlineNanos < 0) return;
            synchronized (this) {
                if (now - deadlineNanos >= 0) finish("timeout", null);
            }
        }

        synchronized AsyncContext listen(AsyncContext context) {
            if (!finished && attached != context) {
                context.addListener(this);
                attached = context;
            }
            return context;
        }

        synchronized boolean listening() { return attached != null; }

        synchronized void finish(String kind, Throwable failure) {
            if (finished) return;
            finished = true;
            long duration = elapsed();
            String ts = Instant.now().toString();
            sender.forget(this);
            boolean completed = kind == null && failure == null && !Boolean.TRUE.equals(request.getAttribute(ABORTED));
            if (duration >= timeout) kind = "timeout";
            else if (disconnected(failure) || Boolean.TRUE.equals(request.getAttribute(ABORTED))) kind = "aborted";
            int status = response.getStatus();
            String errorClass = (String) request.getAttribute(ERROR_CLASS);
            if (kind != null) {
                if (!completed && !response.isCommitted()) status = 0;
                errorClass = null;
            } else {
                if (failure != null) {
                    status = 500;
                    errorClass = failure.getClass().getSimpleName();
                }
                HttpStatus known = HttpStatus.resolve(status);
                if (status >= 400) kind = known == null ? "HTTP_" + status : known.name();
                else errorClass = null;
            }
            String method = METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
            String observedRoute = handlerRoute != null ? handlerRoute : route(request);
            sender.offer(new RequestRecord("request", observedRoute, method, status, duration, ts, kind, errorClass,
                    queue, timeout, io.dbMillis(), io.externalMillis()));
        }

        @Override public void onComplete(AsyncEvent event) { finish(null, null); }
        @Override public void onTimeout(AsyncEvent event) { finish("timeout", null); }
        @Override public void onError(AsyncEvent event) {
            Throwable cause = event.getThrowable();
            finish(cause == null || cause instanceof IOException ? "aborted" : null, cause);
        }
        @Override public synchronized void onStartAsync(AsyncEvent event) {
            attached = null;
            listen(event.getAsyncContext());
        }
    }
}
