package com.trova.backend.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 파이프라인 stderr 임시 파일을 실행 중에 조금씩 읽어, "TROVA_PROGRESS:{json}" 줄을 찾아 넘긴다(#51).
 * stderr는 파이프 대신 파일로 받고 있어서(stdout JSON 오염 방지), 끝난 뒤 한 번에 읽던 것을
 * 새로 추가된 부분만 주기적으로 읽는 방식으로 바꿨다. 줄이 덜 써진 채 읽히면 다음 번에 이어 붙인다.
 */
class StderrProgressTail {

    static final String MARKER = "TROVA_PROGRESS:";
    private static final Logger log = LoggerFactory.getLogger(StderrProgressTail.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Consumer<PipelineProgress> onProgress;
    private long offset;
    private final StringBuilder partialLine = new StringBuilder();

    StderrProgressTail(Path file, Consumer<PipelineProgress> onProgress) {
        this.file = file;
        this.onProgress = onProgress;
    }

    void poll() {
        byte[] added;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            if (length <= offset) {
                return;
            }
            added = new byte[(int) (length - offset)];
            raf.seek(offset);
            raf.readFully(added);
            offset = length;
        } catch (IOException e) {
            log.debug("파이프라인 stderr 중간 읽기 실패: {}", e.getMessage());
            return;
        }
        // 한국어(여러 바이트 문자)가 읽기 경계에서 잘릴 수 있지만, 완성된 줄만 해석하므로 줄 단위로는 안전하다.
        partialLine.append(new String(added, StandardCharsets.UTF_8));
        int newline;
        while ((newline = partialLine.indexOf("\n")) >= 0) {
            String line = partialLine.substring(0, newline).trim();
            partialLine.delete(0, newline + 1);
            if (line.startsWith(MARKER)) {
                parse(line.substring(MARKER.length())).ifPresent(onProgress);
            }
        }
    }

    private Optional<PipelineProgress> parse(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node.hasNonNull("title")) {
                return Optional.of(new PipelineProgress(node.get("title").asText(), null));
            }
            if (node.has("placeNames") && node.get("placeNames").isArray()) {
                List<String> names = new ArrayList<>();
                node.get("placeNames").forEach(n -> {
                    if (n.isTextual() && !n.asText().isBlank()) {
                        names.add(n.asText());
                    }
                });
                return Optional.of(new PipelineProgress(null, List.copyOf(names)));
            }
        } catch (IOException e) {
            // 중간 결과는 보여주기용이라, 형식이 깨졌으면 조용히 건너뛴다(최종 결과는 stdout JSON으로 따로 받는다).
            log.debug("파이프라인 진행 표식 해석 실패: {}", json);
        }
        return Optional.empty();
    }
}
