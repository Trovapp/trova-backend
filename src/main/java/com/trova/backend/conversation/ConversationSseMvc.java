package com.trova.backend.conversation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ConversationSseMvc extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !request.getRequestURI().endsWith("/messages/stream");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean initialized = false;
        try {
            chain.doFilter(request, response);
            initialized = true;
        } finally {
            if (request.getAttribute(ConversationStreams.PENDING) instanceof ConversationStreams.Pending pending) {
                // 초기화 후 클라이언트가 즉시 끊어도 이미 수락한 턴은 끝까지 처리한다.
                if (initialized) pending.start();
                else pending.abortBeforeStart();
            }
        }
    }
}
