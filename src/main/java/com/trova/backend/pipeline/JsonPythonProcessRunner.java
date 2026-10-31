package com.trova.backend.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** 보조 Python의 JSON 입력/출력과 소유 프로세스 수명주기. API 재시도나 결과 파싱은 하지 않는다. */
class JsonPythonProcessRunner {
    private static final Logger log = LoggerFactory.getLogger(JsonPythonProcessRunner.class);
    private final Duration timeout;
    private final OwnedProcessTerminator terminator;

    record Output(String stdout, String stderr, int exitCode) { }

    JsonPythonProcessRunner(Duration timeout) {
        this(timeout, new OwnedProcessTerminator());
    }

    JsonPythonProcessRunner(Duration timeout, OwnedProcessTerminator terminator) {
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("process timeout must be positive");
        this.timeout = timeout;
        this.terminator = terminator;
    }

    Output run(String script, String key, Path inputFile, String inputJson, String operation, Long requestId) {
        Path workDir = inputFile.getParent();
        Path stdout = null;
        Path stderr = null;
        Process process = null;
        Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();
        boolean ownedWorkDir = false;
        boolean attempted = false;
        boolean confirmed = false;
        try {
            Files.createDirectories(workDir.getParent());
            Files.createDirectory(workDir);
            ownedWorkDir = true;
            Files.writeString(inputFile, inputJson, StandardCharsets.UTF_8);
            stdout = Files.createTempFile(workDir, "stdout-", ".json");
            stderr = Files.createTempFile(workDir, "stderr-", ".log");
            ProcessBuilder builder = new ProcessBuilder("python3", script, inputFile.toString());
            builder.environment().put("GEMINI_API_KEY", key);
            builder.redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
            process = builder.start();
            long deadline = System.nanoTime() + timeout.toNanos();
            boolean finished = false;
            while (!finished && System.nanoTime() < deadline) {
                OwnedProcessTerminator.capture(process, descendants);
                finished = process.waitFor(100, TimeUnit.MILLISECONDS);
            }
            if (!finished) throw new PipelineException(operation + " 실행 시간 초과: requestId=" + requestId);
            attempted = true;
            confirmed = terminator.terminate(process, descendants, requestId);
            if (!confirmed) throw new PipelineException(operation + " 프로세스 종료를 확인하지 못했습니다: " + requestId);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("프로세스 정리 중 인터럽트");
            return new Output(Files.readString(stdout, StandardCharsets.UTF_8),
                    Files.readString(stderr, StandardCharsets.UTF_8), process.exitValue());
        } catch (IOException error) {
            throw new PipelineException(operation + " 실행/파일 처리 실패: " + error.getMessage(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new PipelineException(operation + " 실행 중 인터럽트: " + error.getMessage(), error);
        } finally {
            if (!attempted) confirmed = terminator.terminate(process, descendants, requestId);
            if (confirmed && ownedWorkDir) {
                deleteWorkDir(workDir, operation, requestId);
            } else if (!confirmed) {
                log.error("{} {} 종료 미확인 — 파일 보존: workDir={}, stdout={}, stderr={}",
                        operation, requestId, workDir, stdout, stderr);
            }
        }
    }

    private void deleteWorkDir(Path workDir, String operation, Long requestId) {
        if (!Files.exists(workDir)) return;
        try (Stream<Path> paths = Files.walk(workDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException error) { log.warn("{} {} 작업 파일 삭제 실패: {}", operation, requestId, path, error); }
            });
        } catch (IOException error) {
            log.warn("{} {} 작업 폴더 정리 실패: {}", operation, requestId, workDir, error);
        }
    }
}
