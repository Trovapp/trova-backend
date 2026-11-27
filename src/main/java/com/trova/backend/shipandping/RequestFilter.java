package com.trova.backend.shipandping;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

final class RequestFilter extends OncePerRequestFilter {
    static final String ERROR_CLASS = RequestFilter.class.getName() + ".errorClass";
    private static final Pattern VARIABLE = Pattern.compile("\\{[^/}]+}");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "TRACE");
    private final Sender sender;
    private final ApiRoutes routes = new ApiRoutes();

    RequestFilter(Sender sender) {
        this.sender = sender;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || "OPTIONS".equals(request.getMethod());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        long start = System.nanoTime();
        Throwable failure = null;
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            Object mapping = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String route = mapping instanceof String template && template.startsWith("/api/")
                    ? VARIABLE.matcher(template).replaceAll(":id")
                    : routes.lookup(request.getRequestURI().substring(request.getContextPath().length()), request.getMethod());
            // 단일 비동기 응답은 완료 시점에 측정하고, SSE는 연결 수립 시간만 기록한다.
            Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
            if (failure == null && request.isAsyncStarted() && handler instanceof HandlerMethod mapped
                    && CompletionStage.class.isAssignableFrom(mapped.getMethod().getReturnType())) {
                WebAsyncUtils.getAsyncManager(request).getAsyncWebRequest().addCompletionHandler(
                        () -> record(request, response, route, start, null));
            } else {
                record(request, response, route, start, failure);
            }
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response, String route,
                        long start, Throwable failure) {
        int status = failure == null ? response.getStatus() : 500;
        HttpStatus known = HttpStatus.resolve(status);
        String code = status < 400 ? null : known == null ? "HTTP_" + status : known.name();
        String errorClass = (String) request.getAttribute(ERROR_CLASS);
        if (errorClass == null && failure != null) errorClass = failure.getClass().getSimpleName();
        String method = METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
        sender.offer(new RequestRecord("request", route, method, status, (System.nanoTime() - start) / 1_000_000,
                Instant.now().toString(), code, status >= 400 ? errorClass : null));
    }
}
