package com.trova.backend.events;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class TripSseMvc implements WebMvcConfigurer, AsyncHandlerInterceptor {
    static final String STREAM = TripSseMvc.class.getName();

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/trips/*/events");
    }

    @Override public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getAttribute(STREAM) instanceof TripStream stream) stream.ready(request.getAsyncContext());
    }
}
