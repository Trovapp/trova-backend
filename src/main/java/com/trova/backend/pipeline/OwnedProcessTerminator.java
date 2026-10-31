package com.trova.backend.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 소유 부모/관찰 자식 종료를 공유 예산 안에 확인한다. OS 프로세스 그룹 보장은 아니다. */
class OwnedProcessTerminator {
    private static final Logger log = LoggerFactory.getLogger(OwnedProcessTerminator.class);
    private static final long TERMINATION_TIMEOUT_SECONDS = 5;

    static void capture(Process process, Map<Long, ProcessHandle> descendants) {
        process.descendants().forEach(child -> descendants.put(child.pid(), child));
    }

    boolean terminate(Process process, Map<Long, ProcessHandle> descendants, Long jobId) {
        if (process == null) {
            return true;
        }
        boolean interrupted = Thread.interrupted();
        boolean complete = true;
        try {
            try {
                capture(process, descendants);
            } catch (RuntimeException error) {
                complete = false;
                log.warn("ProcessingJob {} 하위 프로세스 탐색 실패 — 부모/관찰 자식 종료는 계속 시도", jobId, error);
            }
            // 일부 탐색/종료가 실패해도 다른 소유 프로세스의 정리는 계속한다.
            for (ProcessHandle child : new ArrayList<>(descendants.values())) {
                try {
                    child.descendants().forEach(nested -> descendants.put(nested.pid(), nested));
                } catch (RuntimeException error) {
                    complete = false;
                    log.warn("ProcessingJob {} 하위 트리 탐색 실패: {}", jobId, child.pid(), error);
                }
            }
            try {
                process.destroyForcibly();
            } catch (RuntimeException error) {
                complete = false;
                log.warn("ProcessingJob {} 부모 종료 시도 실패", jobId, error);
            }
            for (ProcessHandle child : descendants.values()) {
                try {
                    child.destroyForcibly();
                } catch (RuntimeException error) {
                    complete = false;
                    log.warn("ProcessingJob {} 하위 프로세스 종료 시도 실패: {}", jobId, child.pid(), error);
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TERMINATION_TIMEOUT_SECONDS);
            while (process.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                try {
                    process.waitFor(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            for (ProcessHandle child : descendants.values()) {
                while (child.isAlive()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return false;
                    try {
                        child.onExit().get(remaining, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException error) {
                        interrupted = true;
                    } catch (ExecutionException | TimeoutException error) {
                        log.warn("ProcessingJob {} 하위 프로세스 종료 대기 실패: {}", jobId, child.pid(), error);
                        return false;
                    }
                }
            }
            return complete;
        } catch (RuntimeException error) {
            log.error("ProcessingJob {} 소유 프로세스 정리 실패", jobId, error);
            return false;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

}
