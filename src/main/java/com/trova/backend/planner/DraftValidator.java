package com.trova.backend.planner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.recommendation.GooglePlacesHoursResponse;
import com.trova.backend.replan.GeoUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 초안 규칙 검증(#106 3일차) — AI 없이 코드로만 판정한다. 상수는 eval/itinerary/score.py와 같다(2026-10-03 승인):
 * 연속 이동 직선 30km, 하루 2~7곳. 영업시간을 모르는 장소는 위반이 아니라 판정 불가로 센다.
 * ERROR는 수정 대상이자 저장 전 알려야 할 문제, WARNING은 알려주되 고치지 못해도 저장을 막지 않는다(식사·적은 분량).
 */
public final class DraftValidator {

    static final double MAX_HOP_KM = 30;
    static final int DAY_MIN = 2;
    static final int DAY_MAX = 7;
    static final LocalTime LUNCH_FROM = LocalTime.of(11, 0);
    static final LocalTime LUNCH_TO = LocalTime.of(14, 0);
    static final LocalTime DINNER_FROM = LocalTime.of(17, 0);
    static final LocalTime DINNER_TO = LocalTime.of(20, 0);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DraftValidator() {
    }

    public enum Severity { ERROR, WARNING }

    public record Violation(String type, Severity severity, int day, Long placeId, String message) {
    }

    public record Report(List<Violation> violations, int unknownHours) {
        public long errors() {
            return violations.stream().filter(v -> v.severity() == Severity.ERROR).count();
        }
    }

    public static Report validate(DraftGenerator.Draft draft, Map<Long, SavedPlace> places, int days, LocalDate startDate) {
        List<Violation> out = new ArrayList<>();
        int unknown = 0;
        int total = draft.days().stream().mapToInt(d -> d.items().size()).sum() + draft.excluded().size();
        int scheduled = draft.days().stream().mapToInt(d -> d.items().size()).sum();
        boolean anyRestaurant = places.values().stream().anyMatch(p -> "restaurant".equals(p.getCategory()));
        if (draft.days().size() != days) {
            out.add(new Violation("DAYS", Severity.ERROR, 0, null, "요청 " + days + "일, 일정 " + draft.days().size() + "일"));
        }
        for (DraftGenerator.Day day : draft.days()) {
            List<DraftGenerator.Item> items = day.items();
            // 영업
            for (DraftGenerator.Item it : items) {
                SavedPlace p = places.get(it.placeId());
                if (p == null) {
                    out.add(new Violation("NOT_IN_VIDEO", Severity.ERROR, day.day(), it.placeId(), it.name() + "은 영상에 없는 장소"));
                    continue;
                }
                if (startDate == null || p.getOpeningPeriods() == null) {
                    unknown++;
                    continue;
                }
                LocalDate date = startDate.plusDays(day.day() - 1);
                Optional<Boolean> open = OpeningHoursService.isOpenOn(p.getOpeningPeriods(), date);
                if (open.isEmpty()) {
                    unknown++;
                } else if (!open.get()) {
                    out.add(new Violation("CLOSED", Severity.ERROR, day.day(), it.placeId(), it.name() + " " + day.day() + "일차 휴무"));
                } else if (!withinHours(p.getOpeningPeriods(), date, it.start(), it.end())) {
                    out.add(new Violation("OUTSIDE_HOURS", Severity.ERROR, day.day(), it.placeId(),
                            it.name() + " " + it.start() + "~" + it.end() + "는 영업시간 밖"));
                }
            }
            // 이동
            for (int i = 0; i + 1 < items.size(); i++) {
                DraftGenerator.Item a = items.get(i);
                DraftGenerator.Item b = items.get(i + 1);
                if (a.latitude() != null && b.latitude() != null) {
                    double km = GeoUtils.haversineKm(a.latitude(), a.longitude(), b.latitude(), b.longitude());
                    if (km > MAX_HOP_KM) {
                        out.add(new Violation("HOP_TOO_FAR", Severity.ERROR, day.day(), b.placeId(),
                                a.name() + "→" + b.name() + " " + Math.round(km) + "km"));
                    }
                }
            }
            // 분량 — 너무 많으면 고쳐야 하고, 장소가 적어 생긴 빈 날은 알려만 준다.
            if (items.size() > DAY_MAX) {
                out.add(new Violation("DAY_TOO_FULL", Severity.ERROR, day.day(), null, day.day() + "일차 " + items.size() + "곳"));
            } else if (items.isEmpty() && scheduled >= DAY_MIN * draft.days().size()) {
                // 장소가 날마다 2곳씩 나눌 만큼 있는데 빈 날이 있으면 고쳐야 한다(#108 재측정: 3일 요청에 [5, 3, 0]).
                out.add(new Violation("EMPTY_DAY", Severity.ERROR, day.day(), null,
                        day.day() + "일차가 비어 있음 — 다른 날 장소를 나눠 넣어야 함"));
            } else if (items.size() < DAY_MIN && total > 1) {
                out.add(new Violation("DAY_TOO_LIGHT", Severity.WARNING, day.day(), null, day.day() + "일차 " + items.size() + "곳"));
            }
            // 식사 — 식당이 하나라도 있을 때만, 3곳 이상인 날에 점심·저녁 시간대 식당이 없으면 알린다.
            if (anyRestaurant && items.size() >= 3) {
                if (!hasMeal(items, LUNCH_FROM, LUNCH_TO)) {
                    out.add(new Violation("NO_LUNCH", Severity.WARNING, day.day(), null, day.day() + "일차 점심 시간대 식당 없음"));
                }
                if (!hasMeal(items, DINNER_FROM, DINNER_TO) && items.get(items.size() - 1).end().isAfter(DINNER_FROM)) {
                    out.add(new Violation("NO_DINNER", Severity.WARNING, day.day(), null, day.day() + "일차 저녁 시간대 식당 없음"));
                }
            }
        }
        return new Report(out, unknown);
    }

    private static boolean hasMeal(List<DraftGenerator.Item> items, LocalTime from, LocalTime to) {
        return items.stream().anyMatch(it -> "restaurant".equals(it.category())
                && !it.start().isBefore(from) && it.start().isBefore(to));
    }

    /** 방문 구간이 그 요일의 영업 구간 하나 안에 들어가는지. 자정을 넘겨 닫는 구간은 그날 24시까지로 본다. */
    static boolean withinHours(String periodsJson, LocalDate date, LocalTime start, LocalTime end) {
        try {
            List<GooglePlacesHoursResponse.Period> periods = MAPPER.readValue(periodsJson, new TypeReference<>() {
            });
            if (periods.stream().anyMatch(p -> p.close() == null)) {
                return true;
            }
            int googleDay = date.getDayOfWeek().getValue() % 7;
            for (GooglePlacesHoursResponse.Period p : periods) {
                if (p.open() == null || p.open().day() == null || p.open().day() != googleDay) {
                    continue;
                }
                LocalTime open = LocalTime.of(nz(p.open().hour()), nz(p.open().minute()));
                boolean overnight = p.close().day() == null || !p.close().day().equals(p.open().day());
                LocalTime close = overnight ? LocalTime.MAX : LocalTime.of(nz(p.close().hour()), nz(p.close().minute()));
                if (!start.isBefore(open) && !end.isAfter(close)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true; // 읽지 못하면 위반으로 보지 않는다(판정 불가와 같은 취급)
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
