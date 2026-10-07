package com.trova.backend.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "이 영상 3개로 부산 1박 2일 짜 줘" 같은 요청에서 일수와 시작일을 읽는다.
 * 정해진 표현(N박 M일, 당일, M월 D일, YYYY-MM-DD)은 코드로 먼저 읽고, 일수를 못 읽었을 때만 Gemini를 한 번 부른다
 * — 무료 한도를 아끼고, 자주 쓰는 표현은 테스트로 확실히 보장하려고(2026-10-03 설계).
 */
@Component
public class PlanRequestParser {

    static final int MAX_DAYS = 7;
    private static final Pattern NIGHTS_DAYS = Pattern.compile("(\\d+)\\s*박\\s*(\\d+)\\s*일");
    private static final Pattern DAYS_ONLY = Pattern.compile("(\\d+)\\s*일\\s*(?:짜리|동안|일정|여행|코스)");
    private static final Pattern SAME_DAY = Pattern.compile("당일|하루\\s*(?:코스|일정|여행)?");
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GeminiJsonClient geminiJsonClient;
    private final Clock clock;

    @Autowired
    public PlanRequestParser(GeminiJsonClient geminiJsonClient) {
        this(geminiJsonClient, Clock.systemDefaultZone());
    }

    PlanRequestParser(GeminiJsonClient geminiJsonClient, Clock clock) {
        this.geminiJsonClient = geminiJsonClient;
        this.clock = clock;
    }

    /** source: CODE(정해진 표현), AI(Gemini), DEFAULT(둘 다 못 읽어 당일로 둠) — 결과에 남겨 측정에 쓴다. */
    public record PlanRequest(int days, LocalDate startDate, String source) {
    }

    public PlanRequest parse(String message) {
        String text = message == null ? "" : message;
        LocalDate startDate = parseDate(text).orElse(null);
        // "11월 1일 여행"의 "1일 여행"을 일수로 읽지 않게 날짜 표현을 지운 뒤 일수를 읽는다.
        Optional<Integer> days = parseDays(MONTH_DAY.matcher(ISO_DATE.matcher(text).replaceAll(" ")).replaceAll(" "));
        if (days.isPresent()) {
            return new PlanRequest(days.get(), startDate, "CODE");
        }
        return parseWithAi(text, startDate).orElse(new PlanRequest(1, startDate, "DEFAULT"));
    }

    public static Optional<Integer> parseDays(String text) {
        Matcher m = NIGHTS_DAYS.matcher(text);
        if (m.find()) {
            return clampDays(Integer.parseInt(m.group(2)));
        }
        m = DAYS_ONLY.matcher(text);
        if (m.find()) {
            return clampDays(Integer.parseInt(m.group(1)));
        }
        if (SAME_DAY.matcher(text).find()) {
            return Optional.of(1);
        }
        return Optional.empty();
    }

    private static Optional<Integer> clampDays(int days) {
        return days >= 1 && days <= MAX_DAYS ? Optional.of(days) : Optional.empty();
    }

    Optional<LocalDate> parseDate(String text) {
        LocalDate today = LocalDate.now(clock);
        try {
            Matcher iso = ISO_DATE.matcher(text);
            if (iso.find()) {
                return Optional.of(LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)),
                        Integer.parseInt(iso.group(3))));
            }
            Matcher md = MONTH_DAY.matcher(text);
            if (md.find()) {
                // 연도가 없으면 오늘 이후로 가장 가까운 그 날짜(지난 날짜면 내년).
                LocalDate date = LocalDate.of(today.getYear(), Integer.parseInt(md.group(1)), Integer.parseInt(md.group(2)));
                return Optional.of(date.isBefore(today) ? date.plusYears(1) : date);
            }
        } catch (DateTimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private Optional<PlanRequest> parseWithAi(String text, LocalDate startDate) {
        String prompt = """
                여행 일정 요청 문장에서 여행 일수와 시작 날짜를 읽어 JSON 객체 하나로만 답하세요.
                - days: 여행 일수(정수, 1~%d). 당일이면 1, "1박 2일"이면 2. 문장에서 알 수 없으면 null.
                - startDate: 시작 날짜 "YYYY-MM-DD"(오늘은 %s). 문장에서 알 수 없으면 null. 짐작하지 마세요.
                요청 문장: %s
                """.formatted(MAX_DAYS, LocalDate.now(clock), text);
        return geminiJsonClient.generateJson(prompt, "trip-plan.parse-request").flatMap(json -> {
            try {
                JsonNode node = MAPPER.readTree(json);
                JsonNode daysNode = node.get("days");
                if (daysNode == null || !daysNode.canConvertToInt()) {
                    return Optional.empty();
                }
                Optional<Integer> days = clampDays(daysNode.asInt());
                if (days.isEmpty()) {
                    return Optional.empty();
                }
                LocalDate date = startDate;
                JsonNode dateNode = node.get("startDate");
                if (date == null && dateNode != null && dateNode.isTextual()) {
                    date = parseDate(dateNode.asText()).orElse(null);
                }
                return Optional.of(new PlanRequest(days.get(), date, "AI"));
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }
}
