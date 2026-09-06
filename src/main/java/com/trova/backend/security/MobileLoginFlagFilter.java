package com.trova.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class MobileLoginFlagFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain
    ) throws ServletException, IOException {
        if ("true".equals(request.getParameter("mobile"))) {
            HttpSession session = request.getSession(true);
            session.setAttribute("MOBILE_LOGIN", Boolean.TRUE);
        }
        filterChain.doFilter(request, response);
    }
}
