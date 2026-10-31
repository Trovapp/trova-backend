package com.trova.backend.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StderrProgressTailTest {
    @TempDir Path temporary;

    @Test
    void 한국어와_이모지의_모든_바이트_분할_위치를_보존한다() throws Exception {
        byte[] line = "TROVA_PROGRESS:{\"title\":\"제주 🌊 카페\"}\n".getBytes(StandardCharsets.UTF_8);
        for (int split = 0; split <= line.length; split++) {
            Path file = temporary.resolve("split-" + split);
            Files.write(file, Arrays.copyOfRange(line, 0, split));
            List<PipelineProgress> events = new ArrayList<>();
            StderrProgressTail tail = new StderrProgressTail(file, events::add);
            tail.poll();
            if (split < line.length) assertThat(events).as("개행 전 split=%s", split).isEmpty();
            Files.write(file, Arrays.copyOfRange(line, split, line.length), StandardOpenOption.APPEND);
            tail.poll(); tail.poll();
            assertThat(events).as("split=%s", split).containsExactly(new PipelineProgress("제주 🌊 카페", null));
        }
    }

    @Test
    void 한_바이트씩_써도_일반로그와_잘못된_JSON_뒤_진행을_온전히_읽는다() throws Exception {
        String text = "일반 stderr 경고 🧳\n\n"
                + "TROVA_PROGRESS:{\"title\":\"부산 바다 🌊\"}\r\n"
                + "TROVA_PROGRESS:{broken}\n"
                + "TROVA_PROGRESS:\nTROVA_PROGRESS:   \nTROVA_PROGRESS:null\n"
                + "TROVA_PROGRESS:{\"placeNames\":[\"성산일출봉\",\"☕ 카페\"]}\n"
                + "TROVA_PROGRESS:{\"title\":\"서울 🧳\"}\n";
        Path file = Files.createFile(temporary.resolve("one-byte"));
        List<PipelineProgress> events = new ArrayList<>();
        StderrProgressTail tail = new StderrProgressTail(file, events::add);
        for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
            Files.write(file, new byte[]{value}, StandardOpenOption.APPEND);
            tail.poll();
        }
        tail.poll();
        assertThat(events).containsExactly(
                new PipelineProgress("부산 바다 🌊", null),
                new PipelineProgress(null, List.of("성산일출봉", "☕ 카페")),
                new PipelineProgress("서울 🧳", null));
    }

    @Test
    void 개행이_없는_줄은_디코딩하지_않고_다음_개행까지_기다린다() throws Exception {
        Path file = temporary.resolve("without-newline");
        Files.writeString(file, "TROVA_PROGRESS:{\"title\":\"경주 첨성대 🌟\"}", StandardCharsets.UTF_8);
        List<PipelineProgress> events = new ArrayList<>();
        StderrProgressTail tail = new StderrProgressTail(file, events::add);
        tail.poll(); tail.poll();
        assertThat(events).isEmpty();
        Files.writeString(file, "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        tail.poll(); tail.poll();
        assertThat(events).containsExactly(new PipelineProgress("경주 첨성대 🌟", null));
    }

    @Test
    void 여러_완성_줄_뒤_불완전한_문자도_다음_poll에서_이어붙인다() throws Exception {
        byte[] first = "TROVA_PROGRESS:{\"title\":\"첫 장소\"}\n".getBytes(StandardCharsets.UTF_8);
        byte[] second = "TROVA_PROGRESS:{\"placeNames\":[\"한강 🚲\"]}\n".getBytes(StandardCharsets.UTF_8);
        int cut = "TROVA_PROGRESS:{\"placeNames\":[\"한".getBytes(StandardCharsets.UTF_8).length - 1;
        Path file = temporary.resolve("mixed");
        Files.write(file, first);
        Files.write(file, Arrays.copyOfRange(second, 0, cut), StandardOpenOption.APPEND);
        List<PipelineProgress> events = new ArrayList<>();
        StderrProgressTail tail = new StderrProgressTail(file, events::add);
        tail.poll();
        assertThat(events).containsExactly(new PipelineProgress("첫 장소", null));
        Files.write(file, Arrays.copyOfRange(second, cut, second.length), StandardOpenOption.APPEND);
        tail.poll();
        assertThat(events).containsExactly(new PipelineProgress("첫 장소", null), new PipelineProgress(null, List.of("한강 🚲")));
    }
}
