package com.trova.backend.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * /api/** 요청의 처리 시간·상태를 메모리에 모았다가 5분 스냅샷 때 가져간다(ServerMetricSnapshotRecorder).
 * Micrometer 히스토그램을 켜는 대신 직접 모으는 이유: 운영 서버 메모리가 작아(E2.1.Micro) 지표를 더 늘리지 않고,
 * 5분 단위 정확한 p50·p90만 필요해서. 5분에 5만 건을 넘으면 넘친 만큼은 버린다(감으로 정한 상한, 실측 아님).
 */
@Component
public class RequestTimingFilter extends OncePerRequestFilter {

    public record Sample(long durationMs, int status) {
    }

    private static final int MAX_SAMPLES = 50_000;
    private final List<Sample> samples = new ArrayList<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        // 처리되지 않은 예외는 여기를 지날 때 응답 상태가 아직 200이고 500은 그 뒤 오류 처리에서 붙는다 — 예외면 500으로 센다.
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            failed = true;
            throw e;
        } finally {
            add(new Sample((System.nanoTime() - start) / 1_000_000, failed ? 500 : response.getStatus()));
        }
    }

    public synchronized void add(Sample sample) {
        if (samples.size() < MAX_SAMPLES) {
            samples.add(sample);
        }
    }

    public synchronized List<Sample> drain() {
        List<Sample> drained = List.copyOf(samples);
        samples.clear();
        return drained;
    }
}
