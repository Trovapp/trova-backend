package com.trova.backend.events;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Start the writer after both MVC initialization and the security filter's final header writes. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TripSseMvc extends OncePerRequestFilter {
    static final String STREAM = TripSseMvc.class.getName();

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"GET".equals(request.getMethod()) || !request.getRequestURI().endsWith("/events");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);
        if (request.isAsyncStarted() && request.getAttribute(STREAM) instanceof TripStream stream)
            stream.ready(request.getAsyncContext());
    }
}
