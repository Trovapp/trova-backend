package com.trova.backend.shipandping;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
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
            // 비동기 스트림은 최초 디스패치 반환 시 연결 수립 결과만 기록한다.
            Object mapping = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String route = mapping instanceof String template && template.startsWith("/api/")
                    ? VARIABLE.matcher(template).replaceAll(":id")
                    : routes.lookup(request.getRequestURI().substring(request.getContextPath().length()), request.getMethod());
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
}
