package com.trova.backend.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.SavedPlace;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 일정 초안 생성(#106 2일차). Gemini가 일차 배정·순서·대략 방문 시각·식사 시간대를 정하고(1번),
 * 코드는 초안이 데이터로 성립하는지(형식)만 확인한다. 형식이 틀리면 무엇이 틀렸는지 알려 한 번 더 받고, 그래도 틀리면 빈 값.
 * 규칙 위반(휴무·이동·분량·식사)은 DraftValidator가 찾고, 코드로 못 고친 것만 {@link #repairRules}로 다시 맡긴다(3일차).
 */
@Component
public class DraftGenerator {

    static final String OPERATION = "trip-plan.draft";
    static final String REPAIR_OPERATION = "trip-plan.draft.repair";
    static final String RULE_REPAIR_OPERATION = "trip-plan.draft.rule-repair";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GeminiJsonClient geminiJsonClient;

    public DraftGenerator(GeminiJsonClient geminiJsonClient) {
        this.geminiJsonClient = geminiJsonClient;
    }

    public record Item(Long placeId, String name, String category, LocalTime start, LocalTime end,
                       Double latitude, Double longitude) {
    }

    public record Day(int day, LocalDate date, List<Item> items) {
    }

    public record Excluded(Long placeId, String name, String reason) {
    }

    public record Draft(List<Day> days, List<Excluded> excluded, List<String> lodging, List<String> assumptions) {
    }

    public record Result(Optional<Draft> draft, int geminiCalls, String failure) {
    }

    /** notes: 이 요청에만 더할 원칙(예: 지역별로 날 나누기 — 시작 전 질문의 답). */
    public Result generate(int days, LocalDate startDate, List<SavedPlace> places, List<String> notes) {
        String prompt = prompt(days, startDate, places, notes);
        Optional<String> first = geminiJsonClient.generateJson(prompt, OPERATION);
        if (first.isEmpty()) {
            return new Result(Optional.empty(), 1, "Gemini 응답 없음");
        }
        Parsed parsed = parse(first.get(), days, startDate, places);
        if (parsed.error() == null) {
            return new Result(Optional.of(parsed.draft()), 1, null);
        }
        String repairPrompt = prompt + "\n\n방금 당신의 응답:\n" + first.get()
                + "\n\n이 응답은 다음 이유로 쓸 수 없습니다: " + parsed.error()
                + "\n같은 규칙을 지켜 JSON만 다시 출력하세요.";
        Optional<String> second = geminiJsonClient.generateJson(repairPrompt, REPAIR_OPERATION);
        if (second.isEmpty()) {
            return new Result(Optional.empty(), 2, "형식 오류 후 Gemini 응답 없음: " + parsed.error());
        }
        Parsed retried = parse(second.get(), days, startDate, places);
        return retried.error() == null
                ? new Result(Optional.of(retried.draft()), 2, null)
                : new Result(Optional.empty(), 2, "형식 오류: " + retried.error());
    }

    /**
     * 규칙 위반을 알려 고친 초안 전체를 다시 받는다(호출 1번). 형식이 틀리거나 응답이 없으면 빈 값 — 호출한 쪽이 이전 초안을 유지한다.
     */
    public Result repairRules(int days, LocalDate startDate, List<SavedPlace> places, List<String> notes, Draft current,
                              List<String> problems) {
        String repairPrompt = prompt(days, startDate, places, notes)
                + "\n\n지금 초안:\n" + toPromptJson(current)
                + "\n\n이 초안에는 다음 문제가 있습니다:\n- " + String.join("\n- ", problems)
                + "\n문제를 고친 초안 전체를 같은 형식의 JSON으로만 다시 출력하세요. 문제가 없는 날은 되도록 그대로 두세요.";
        Optional<String> answer = geminiJsonClient.generateJson(repairPrompt, RULE_REPAIR_OPERATION);
        if (answer.isEmpty()) {
            return new Result(Optional.empty(), 1, "규칙 수정 응답 없음");
        }
        Parsed parsed = parse(answer.get(), days, startDate, places);
        return parsed.error() == null
                ? new Result(Optional.of(parsed.draft()), 1, null)
                : new Result(Optional.empty(), 1, "규칙 수정 형식 오류: " + parsed.error());
    }

    /** Gemini에게 보여줄 때는 받을 때와 같은 모양(placeId·시각만)으로 줄인다. */
    static String toPromptJson(Draft draft) {
        List<Map<String, Object>> days = new ArrayList<>();
        for (Day d : draft.days()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Item it : d.items()) {
                items.add(Map.of("placeId", it.placeId(), "start", it.start().toString(), "end", it.end().toString()));
            }
            days.add(Map.of("day", d.day(), "items", items));
        }
        List<Map<String, Object>> excluded = new ArrayList<>();
        draft.excluded().forEach(e -> excluded.add(Map.of("placeId", e.placeId(), "reason", e.reason())));
        try {
            return MAPPER.writeValueAsString(Map.of("days", days, "excluded", excluded));
        } catch (Exception e) {
            throw new IllegalStateException("초안 JSON 변환 실패", e);
        }
    }

    static String prompt(int days, LocalDate startDate, List<SavedPlace> places, List<String> notes) {
        StringBuilder list = new StringBuilder();
        for (SavedPlace p : places) {
            list.append("- id=").append(p.getId())
                    .append(" | ").append(p.getPlaceName())
                    .append(" | 분류=").append(p.getCategory() == null ? "other" : p.getCategory())
                    .append(" | 지역=").append(p.getRegion() == null ? "모름" : p.getRegion());
            if (p.getLatitude() != null) {
                list.append(" | 좌표=").append(String.format(Locale.ROOT, "%.4f,%.4f", p.getLatitude(), p.getLongitude()));
            }
            list.append(" | ").append(closedDaysNote(p, days, startDate)).append('\n');
        }
        String dates = startDate == null ? "날짜 미정(요일을 모름)"
                : java.util.stream.IntStream.range(0, days)
                .mapToObj(i -> (i + 1) + "일차=" + startDate.plusDays(i) + "(" + weekday(startDate.plusDays(i)) + ")")
                .collect(Collectors.joining(", "));
        return """
                당신은 여행 영상에서 모은 장소로 %d일짜리 일정 초안을 짜는 도구입니다. 날짜: %s.
                아래 장소 목록만 쓰세요. 목록에 없는 장소를 추가하지 마세요.

                원칙:
                - 가까운 장소(좌표)끼리 같은 날에 묶고, 하루 안에서는 동선이 자연스럽게 이어지게 순서를 정하세요.
                - 하루는 대략 09:00~21:00 안에서, 장소마다 머무는 시간을 분류에 맞게 잡으세요(관광 60~120분, 식당 60분, 카페 45~60분, 쇼핑 60분).
                - 식당이 있으면 날마다 점심(11:00~14:00)과 저녁(17:00~20:00) 시간대에 하나씩 넣도록 노력하세요.
                - 관광지(attraction)는 해가 지기 전(18:00 전)에 끝나게 하세요. 18:00 이후에는 식당·카페·쇼핑이나 야경·야시장처럼 밤에 가는 곳을 넣으세요.
                - 휴무로 표시된 날에는 그 장소를 넣지 마세요.
                - 하루 2~7곳이 되게 하세요(하루 최대 7곳). %d일에 다 넣을 수 있으면 모두 넣고, 모든 날에 장소를 나눠 넣으세요(빈 날 없이).
                - 장소를 excluded로 빼도 되는 경우는 이것뿐입니다: 그날 휴무, 다른 장소들과 30km 넘게 떨어짐, 공항·역 같은 지나가는 곳,
                  이미 넣은 장소와 같은 곳(중복), 좌표가 없음, 모든 날이 7곳으로 꽉 참. 효율·취향·시간 여유 같은 이유로는 빼지 마세요.
                - 같은 날 연달아 가는 두 장소는 직선 30km 안이어야 합니다. 다른 장소들과 멀리 떨어진 곳은 excluded로 빼세요.
                - 공항·기차역·터미널처럼 지나가는 곳은 일정에 넣지 말고 excluded로 빼세요.
                - excluded의 reason은 사용자에게 그대로 보여줄 존댓말 한 문장입니다(예: "다른 장소들과 40km 넘게 떨어져 있어 이번 일정에서 뺐어요.").
                %s- 모든 장소는 days나 excluded 중 정확히 한 곳에 한 번만 나와야 합니다.

                장소 목록:
                %s
                JSON 객체 하나로만 답하세요:
                {"days":[{"day":1,"items":[{"placeId":정수,"start":"HH:mm","end":"HH:mm"}]}],
                 "excluded":[{"placeId":정수,"reason":"문장"}]}
                """.formatted(days, dates, days,
                notes.stream().map(n -> "- " + n + "\n").collect(Collectors.joining()), list);
    }

    private static String closedDaysNote(SavedPlace p, int days, LocalDate startDate) {
        if (p.getOpeningPeriods() == null) {
            return "영업정보 없음";
        }
        if (startDate == null) {
            return "영업정보 있음(날짜 미정)";
        }
        List<String> closed = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate d = startDate.plusDays(i);
            if (OpeningHoursService.isOpenOn(p.getOpeningPeriods(), d).filter(open -> !open).isPresent()) {
                closed.add((i + 1) + "일차");
            }
        }
        return closed.isEmpty() ? "일정 기간 중 휴무 없음" : "휴무: " + String.join(", ", closed);
    }

    private static String weekday(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
    }

    record Parsed(Draft draft, String error) {
    }

    static Parsed parse(String json, int days, LocalDate startDate, List<SavedPlace> places) {
        Map<Long, SavedPlace> byId = new HashMap<>();
        places.forEach(p -> byId.put(p.getId(), p));
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            return new Parsed(null, "JSON이 아님");
        }
        Set<Long> seen = new HashSet<>();
        List<Day> dayList = new ArrayList<>();
        JsonNode daysNode = root.path("days");
        if (!daysNode.isArray()) {
            return new Parsed(null, "days 배열이 없음");
        }
        Map<Integer, List<Item>> itemsByDay = new LinkedHashMap<>();
        for (JsonNode d : daysNode) {
            int dayNo = d.path("day").asInt(-1);
            if (dayNo < 1 || dayNo > days) {
                return new Parsed(null, "day는 1~" + days + " 사이여야 함(받은 값 " + d.path("day") + ")");
            }
            for (JsonNode it : d.path("items")) {
                long id = it.path("placeId").asLong(-1);
                SavedPlace p = byId.get(id);
                if (p == null) {
                    return new Parsed(null, "목록에 없는 placeId " + id);
                }
                if (!seen.add(id)) {
                    return new Parsed(null, "placeId " + id + "가 두 번 나옴");
                }
                LocalTime start = time(it.path("start").asText(null));
                LocalTime end = time(it.path("end").asText(null));
                if (start == null || end == null || !end.isAfter(start)) {
                    return new Parsed(null, "placeId " + id + "의 start/end 시각이 잘못됨");
                }
                itemsByDay.computeIfAbsent(dayNo, k -> new ArrayList<>()).add(new Item(id, p.getPlaceName(),
                        p.getCategory(), start, end, p.getLatitude(), p.getLongitude()));
            }
        }
        List<Excluded> excluded = new ArrayList<>();
        for (JsonNode ex : root.path("excluded")) {
            long id = ex.path("placeId").asLong(-1);
            SavedPlace p = byId.get(id);
            if (p == null) {
                return new Parsed(null, "excluded에 목록에 없는 placeId " + id);
            }
            if (!seen.add(id)) {
                return new Parsed(null, "placeId " + id + "가 두 번 나옴");
            }
            excluded.add(new Excluded(id, p.getPlaceName(), ex.path("reason").asText("")));
        }
        List<Long> missing = byId.keySet().stream().filter(id -> !seen.contains(id)).sorted().toList();
        if (!missing.isEmpty()) {
            return new Parsed(null, "빠진 placeId " + missing + " — days나 excluded에 넣어야 함");
        }
        for (int dayNo = 1; dayNo <= days; dayNo++) {
            List<Item> items = new ArrayList<>(itemsByDay.getOrDefault(dayNo, List.of()));
            items.sort((a, b) -> a.start().compareTo(b.start()));
            dayList.add(new Day(dayNo, startDate == null ? null : startDate.plusDays(dayNo - 1), items));
        }
        return new Parsed(new Draft(dayList, excluded, lodging(dayList, List.of()), assumptions(startDate, places)), null);
    }

    public static String toJson(Draft draft) {
        return toJson(draft, List.of(), List.of());
    }

    /**
     * 저장·응답용 JSON. 날짜·시각은 "2026-10-10", "10:00" 문자열로 쓴다(Jackson 2에 시간 모듈이 없어 직접 바꾼다).
     * fixes: 코드·AI가 고친 내용, problems: 끝내 남은 규칙 위반(사용자에게 알릴 것).
     */
    public static String toJson(Draft draft, List<String> fixes, List<String> problems) {
        List<Map<String, Object>> days = new ArrayList<>();
        for (Day d : draft.days()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Item it : d.items()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("placeId", it.placeId());
                m.put("name", it.name());
                m.put("category", it.category());
                m.put("start", it.start().toString());
                m.put("end", it.end().toString());
                m.put("latitude", it.latitude());
                m.put("longitude", it.longitude());
                items.add(m);
            }
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("day", d.day());
            day.put("date", d.date() == null ? null : d.date().toString());
            day.put("items", items);
            days.add(day);
        }
        List<Map<String, Object>> excluded = new ArrayList<>();
        for (Excluded e : draft.excluded()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("placeId", e.placeId());
            m.put("name", e.name());
            m.put("reason", e.reason());
            excluded.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("days", days);
        root.put("excluded", excluded);
        root.put("lodging", draft.lodging());
        root.put("assumptions", draft.assumptions());
        root.put("fixes", fixes);
        root.put("problems", problems);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("초안 JSON 변환 실패", e);
        }
    }

    /** toJson으로 저장한 초안을 다시 읽는다(그래프 상태는 문자열만 들고 다닌다). */
    public static Draft fromJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            List<Day> days = new ArrayList<>();
            for (JsonNode d : root.path("days")) {
                List<Item> items = new ArrayList<>();
                for (JsonNode it : d.path("items")) {
                    items.add(new Item(it.path("placeId").asLong(), it.path("name").asText(null),
                            it.path("category").isNull() ? null : it.path("category").asText(null),
                            time(it.path("start").asText(null)), time(it.path("end").asText(null)),
                            it.path("latitude").isNumber() ? it.path("latitude").asDouble() : null,
                            it.path("longitude").isNumber() ? it.path("longitude").asDouble() : null));
                }
                String date = d.path("date").isTextual() ? d.path("date").asText() : null;
                days.add(new Day(d.path("day").asInt(), date == null ? null : LocalDate.parse(date), items));
            }
            List<Excluded> excluded = new ArrayList<>();
            for (JsonNode e : root.path("excluded")) {
                excluded.add(new Excluded(e.path("placeId").asLong(), e.path("name").asText(null), e.path("reason").asText("")));
            }
            return new Draft(days, excluded, strings(root.path("lodging")), strings(root.path("assumptions")));
        } catch (Exception e) {
            throw new IllegalStateException("초안 JSON 읽기 실패", e);
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static LocalTime time(String text) {
        try {
            return text == null ? null : LocalTime.parse(text.length() == 4 ? "0" + text : text);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 숙소 안내(마지막 날 제외). 영상에 숙소(category=lodging)가 있으면 그날 마지막 장소에서 가장 가까운 영상 숙소를 쓰고,
     * 없으면 "미정 + 마지막 장소 근처"로 안내만 한다(2026-10-03 사용자 승인). 숙소는 방문지가 아니라 일정 칸에는 넣지 않는다.
     */
    public static List<String> lodging(List<Day> days, List<SavedPlace> lodgingPlaces) {
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < days.size() - 1; i++) {
            List<Item> items = days.get(i).items();
            Item last = items.isEmpty() ? null : items.get(items.size() - 1);
            Optional<SavedPlace> stay = nearestLodging(last, lodgingPlaces);
            if (stay.isPresent()) {
                notes.add(days.get(i).day() + "일차 숙소: " + stay.get().getPlaceName() + "(영상 속 숙소)");
            } else if (last != null) {
                notes.add(days.get(i).day() + "일차 숙소 미정 — 마지막 장소(" + last.name() + ") 근처를 추천해요.");
            }
        }
        return notes;
    }

    private static Optional<SavedPlace> nearestLodging(Item last, List<SavedPlace> lodgingPlaces) {
        if (lodgingPlaces.isEmpty()) {
            return Optional.empty();
        }
        if (last == null || last.latitude() == null) {
            return Optional.of(lodgingPlaces.get(0));
        }
        return lodgingPlaces.stream().min(java.util.Comparator.comparingDouble(p -> p.getLatitude() == null
                ? Double.MAX_VALUE
                : com.trova.backend.replan.GeoUtils.haversineKm(last.latitude(), last.longitude(), p.getLatitude(), p.getLongitude())));
    }

    public static final String NO_HOURS_NOTE_PREFIX = "영업시간을 확인하지 못한 장소가 ";

    static List<String> assumptions(LocalDate startDate, List<SavedPlace> places) {
        List<String> notes = new ArrayList<>();
        if (startDate == null) {
            notes.add("날짜를 몰라 요일별 휴무는 확인하지 않았어요. 날짜를 정하면 다시 확인할게요.");
        }
        long noHours = places.stream().filter(p -> p.getOpeningPeriods() == null).count();
        if (noHours > 0) {
            notes.add(NO_HOURS_NOTE_PREFIX + noHours + "곳 있어요.");
        }
        long noCoords = places.stream().filter(p -> p.getLatitude() == null).count();
        if (noCoords > 0) {
            notes.add("위치를 확인하지 못한 장소가 " + noCoords + "곳 있어 동선이 정확하지 않을 수 있어요.");
        }
        notes.add("이동 시간은 직선거리로 어림했어요.");
        return notes;
    }
}
