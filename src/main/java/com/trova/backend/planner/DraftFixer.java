package com.trova.backend.planner;

import com.trova.backend.entity.SavedPlace;
import com.trova.backend.replan.GeoUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 규칙 위반 중 코드로 확실히 고칠 수 있는 것만 고친다(#106 3일차) — AI 수정보다 먼저 돌려 Gemini 호출을 아낀다.
 * 1) 휴무인 날에 잡힌 장소: 그 장소가 여는 날 중 자리가 있는 날로 옮기고(동선상 가장 덜 돌아가는 자리), 없으면 뺀다.
 * 2) 하루 안 순서: 식당 자리(점심·저녁)는 그대로 두고 나머지를 가까운 곳 순으로 다시 놓아 총 이동거리가 줄 때만 바꾼다.
 *    시각 칸은 원래 자리의 것을 그대로 쓴다 — 대략 시각이라 머무는 시간이 조금 달라지는 건 받아들인다(2026-10-03 승인).
 * 3) 근거 없이 뺀 장소 되살리기(#108): AI가 자리가 남는데도 "효율", "시간 부족" 같은 이유로 장소를 빼는 일이 잦았다
 *    (Part B 측정에서 뺀 93곳 중 84곳이 휴무·교통시설·숙소가 아닌 이유). 사실로 확인되는 경우만 빼둔 채로 두고 —
 *    좌표 없음, 공항·역 같은 지나가는 곳, 이미 넣은 곳과 150m 안(중복), 넣은 곳 모두와 30km 넘게 떨어짐, 그날 휴무 — 나머지는 하루 7곳 미만인 날 중
 *    연속 이동이 모두 30km 안이고 21시 안에 끝나며 영업시간 위반이 늘지 않는 자리에 넣는다. 앞 장소 시각은 그대로 두고
 *    넣은 자리부터 겹치는 만큼만 뒤로 민다(AI가 잡은 저녁 시간 같은 빈칸을 지키려고).
 *    #112: 식당은 비어 있는 식사 칸에만, 관광지는 18시까지 끝나는 자리에만, 원래 채워진 식사 칸을 비우지 않을 때만 되살린다.
 * 5) 해 진 뒤 관광지(#112): AI가 18시 넘어 끝나는 관광지를 넣으면 그날 앞쪽의 카페·쇼핑 자리와 바꾼다. 이름에 야경·야시장·전망대·
 *    타워·분수·야간 등이 들어간 곳은 밤에 가는 곳이라 예외(사용자 결정). 바꾼 날도 30km·21시·영업시간·식사 칸을 지킬 때만.
 * 4) 식사 칸 맞추기(#110): 그날 식당이 있는데 점심(11~14시)·저녁(17~20시) 칸이 비어 있으면 그 식당을 칸에 맞게 옮긴다
 *    (#108 재측정에서 식사 경고 13건 중 11건이 이 경우 — 예: 식당 3곳이 09:00·10:10·17:40에 몰려 점심이 빔).
 *    아침(10시 반 전) 식당은 아침 식사로 보고 옮기지 않는다(사용자 결정). 옮긴 날도 연속 이동 30km 안·21시 안·영업시간 위반이
 *    늘지 않을 때만 바꾸고, 다른 날 식당을 가져오지는 않는다(날 배정은 AI 몫).
 * 영업시간 밖·하루 과다·먼 이동은 날 배정 자체를 바꿔야 해서 AI 수정에 맡긴다.
 */
public final class DraftFixer {

    private DraftFixer() {
    }

    public record Fixed(DraftGenerator.Draft draft, List<String> fixes) {
    }

    public static Fixed fix(DraftGenerator.Draft draft, Map<Long, SavedPlace> places, LocalDate startDate) {
        List<String> fixes = new ArrayList<>();
        List<List<DraftGenerator.Item>> days = new ArrayList<>();
        draft.days().forEach(d -> days.add(new ArrayList<>(d.items())));
        List<DraftGenerator.Excluded> excluded = new ArrayList<>(draft.excluded());

        if (startDate != null) {
            moveClosed(days, places, startDate, excluded, fixes);
        }
        restoreExcluded(days, places, startDate, excluded, fixes);
        for (int i = 0; i < days.size(); i++) {
            LocalDate date = startDate == null ? null : startDate.plusDays(i);
            days.set(i, fitMeals(days.get(i), places, date, i + 1, fixes));
        }
        for (int i = 0; i < days.size(); i++) {
            List<DraftGenerator.Item> reordered = reorder(days.get(i));
            if (!reordered.equals(days.get(i))) {
                fixes.add((i + 1) + "일차 순서를 가까운 곳 순으로 바꿨어요(" + Math.round(pathKm(days.get(i)))
                        + "km → " + Math.round(pathKm(reordered)) + "km).");
                days.set(i, reordered);
            }
        }
        // 순서 바꾸기 뒤에 둔다 — 가까운 곳 순으로 바꾸다 관광지가 저녁 칸으로 갈 수 있어서.
        for (int i = 0; i < days.size(); i++) {
            LocalDate date = startDate == null ? null : startDate.plusDays(i);
            days.set(i, daylightSwap(days.get(i), places, date, i + 1, fixes));
        }
        List<DraftGenerator.Day> newDays = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            newDays.add(new DraftGenerator.Day(i + 1, draft.days().get(i).date(), days.get(i)));
        }
        return new Fixed(new DraftGenerator.Draft(newDays, excluded, draft.lodging(), draft.assumptions()), fixes);
    }

    private static void moveClosed(List<List<DraftGenerator.Item>> days, Map<Long, SavedPlace> places, LocalDate startDate,
                                   List<DraftGenerator.Excluded> excluded, List<String> fixes) {
        for (int from = 0; from < days.size(); from++) {
            LocalDate date = startDate.plusDays(from);
            for (DraftGenerator.Item it : new ArrayList<>(days.get(from))) {
                SavedPlace p = places.get(it.placeId());
                if (p == null || !OpeningHoursService.isOpenOn(p.getOpeningPeriods(), date).equals(Optional.of(false))) {
                    continue;
                }
                days.get(from).remove(it);
                Optional<Integer> to = bestOpenDay(days, p, it, startDate);
                if (to.isPresent()) {
                    List<DraftGenerator.Item> target = days.get(to.get());
                    target.add(insertAt(target, it), it);
                    retime(target);
                    fixes.add(Josa.eunNeun(it.name()) + " " + (from + 1) + "일차 휴무라 " + (to.get() + 1) + "일차로 옮겼어요.");
                } else {
                    excluded.add(new DraftGenerator.Excluded(it.placeId(), it.name(), "여행 날짜에 문을 여는 날이 없어 뺐어요."));
                    fixes.add(Josa.eunNeun(it.name()) + " 여는 날이 없어 뺐어요.");
                }
            }
        }
    }

    static final double DUPLICATE_METERS = 150;
    static final LocalTime DAY_END = LocalTime.of(21, 0);
    private static final java.util.regex.Pattern TRANSIT =
            java.util.regex.Pattern.compile("(공항|역|터미널|정류장|정류소|휴게소|IC)$");

    private static void restoreExcluded(List<List<DraftGenerator.Item>> days, Map<Long, SavedPlace> places, LocalDate startDate,
                                        List<DraftGenerator.Excluded> excluded, List<String> fixes) {
        // 이미 넣은 장소에서 가까운 것부터 — 먼저 넣은 장소가 뒤 장소의 자리를 막는 일을 줄인다.
        List<DraftGenerator.Excluded> candidates = new ArrayList<>(excluded);
        candidates.sort(java.util.Comparator.comparingDouble(e -> nearestScheduledKm(days, places.get(e.placeId()))));
        for (DraftGenerator.Excluded e : candidates) {
            SavedPlace p = places.get(e.placeId());
            if (!restorable(days, p)) {
                continue;
            }
            DraftGenerator.Item item = newItem(p);
            Integer bestDay = null;
            List<DraftGenerator.Item> bestTrial = null;
            double bestAdded = Double.MAX_VALUE;
            for (int d = 0; d < days.size(); d++) {
                List<DraftGenerator.Item> day = days.get(d);
                LocalDate date = startDate == null ? null : startDate.plusDays(d);
                if (day.size() >= DraftValidator.DAY_MAX
                        || (date != null && OpeningHoursService.isOpenOn(p.getOpeningPeriods(), date).equals(Optional.of(false)))) {
                    continue;
                }
                for (List<DraftGenerator.Item> trial : restoreTrials(day, item)) {
                    if (!fits(trial, day, places, date) || !keepsMeals(trial, day) || !inDaylight(trial, item)) {
                        continue;
                    }
                    double added = pathKm(trial) - pathKm(day);
                    if (added < bestAdded) {
                        bestAdded = added;
                        bestDay = d;
                        bestTrial = trial;
                    }
                }
            }
            if (bestDay != null) {
                days.set(bestDay, bestTrial);
                excluded.remove(e);
                fixes.add(Josa.eunNeun(p.getPlaceName()) + " " + (bestDay + 1) + "일차에 자리가 있어 다시 넣었어요.");
            }
        }
    }

    static final LocalTime DAYLIGHT_END = LocalTime.of(18, 0);

    /**
     * 되살릴 자리 후보(#112). 식당은 그날 비어 있는 식사 칸(점심·저녁)에만 — 칸이 다 차 있으면 후보가 없다(저녁을 두 번 넣지 않으려고).
     * 나머지는 모든 자리를 후보로 두고, 관광지는 inDaylight로 18시까지 끝나는 자리만 남긴다.
     */
    private static List<List<DraftGenerator.Item>> restoreTrials(List<DraftGenerator.Item> day, DraftGenerator.Item item) {
        List<List<DraftGenerator.Item>> trials = new ArrayList<>();
        if ("restaurant".equals(item.category())) {
            LocalTime[][] windows = {{DraftValidator.LUNCH_FROM, DraftValidator.LUNCH_TO},
                    {DraftValidator.DINNER_FROM, DraftValidator.DINNER_TO}};
            for (LocalTime[] w : windows) {
                if (hasMealIn(day, w[0], w[1])) {
                    continue;
                }
                for (int k = 0; k <= day.size(); k++) {
                    List<DraftGenerator.Item> trial = placeMeal(day, k, item, w[0]);
                    if (trial.get(k).start().isBefore(w[1])) {
                        trials.add(trial);
                    }
                }
            }
            return trials;
        }
        for (int k = 0; k <= day.size(); k++) {
            trials.add(insertAndShift(day, k, item));
        }
        return trials;
    }

    /** 되살린 장소 때문에 원래 채워져 있던 점심·저녁 칸이 비면 안 된다. */
    private static boolean keepsMeals(List<DraftGenerator.Item> trial, List<DraftGenerator.Item> original) {
        return mealsCovered(trial) >= mealsCovered(original);
    }

    private static int mealsCovered(List<DraftGenerator.Item> items) {
        return (hasMealIn(items, DraftValidator.LUNCH_FROM, DraftValidator.LUNCH_TO) ? 1 : 0)
                + (hasMealIn(items, DraftValidator.DINNER_FROM, DraftValidator.DINNER_TO) ? 1 : 0);
    }

    private static final java.util.regex.Pattern NIGHT_SPOT =
            java.util.regex.Pattern.compile("야경|야시장|전망대|타워|분수|야간|불꽃|야행|루프탑|night", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 18시 전에 끝나야 하는 관광지인지 — 이름으로 알 수 있는 밤 명소는 빼고. */
    static boolean daylightOnly(DraftGenerator.Item it) {
        return "attraction".equals(it.category()) && (it.name() == null || !NIGHT_SPOT.matcher(it.name()).find());
    }

    private static boolean isDark(DraftGenerator.Item it) {
        return daylightOnly(it) && it.end().isAfter(DAYLIGHT_END);
    }

    /**
     * 18시 넘어 끝나는 관광지를 그날 앞쪽의 카페·쇼핑(·기타) 자리와 바꾼다. 자리를 바꾼 뒤 바뀐 자리부터 시각을 다시 맞춘다
     * (각 자리의 원래 시작 시각과 앞 장소 끝 + 이동 중 늦은 쪽). 해 진 뒤 관광지가 줄고 다른 규칙이 깨지지 않을 때만 바꾼다.
     */
    static List<DraftGenerator.Item> daylightSwap(List<DraftGenerator.Item> day, Map<Long, SavedPlace> places, LocalDate date,
                                                  int dayNo, List<String> fixes) {
        List<DraftGenerator.Item> cur = day;
        for (int guard = 0; guard < day.size(); guard++) {
            int darkIdx = -1;
            for (int i = 0; i < cur.size(); i++) {
                if (isDark(cur.get(i))) {
                    darkIdx = i;
                    break;
                }
            }
            if (darkIdx < 0) {
                break;
            }
            List<DraftGenerator.Item> best = null;
            for (int j = 0; j < darkIdx; j++) {
                DraftGenerator.Item partner = cur.get(j);
                if ("attraction".equals(partner.category()) || "restaurant".equals(partner.category())) {
                    continue;
                }
                List<DraftGenerator.Item> trial = swapAndReflow(cur, j, darkIdx);
                if (darkCount(trial) >= darkCount(cur) || !fits(trial, cur, places, date) || !keepsMeals(trial, cur)) {
                    continue;
                }
                if (best == null || pathKm(trial) < pathKm(best)) {
                    best = trial;
                }
            }
            if (best == null) {
                break;
            }
            DraftGenerator.Item dark = cur.get(darkIdx);
            DraftGenerator.Item moved = best.stream().filter(it -> it.placeId().equals(dark.placeId())).findFirst().orElseThrow();
            fixes.add(Josa.eunNeun(dark.name()) + " 해가 진 뒤라 " + dayNo + "일차 " + moved.start() + "로 앞당겼어요.");
            cur = best;
        }
        return cur;
    }

    private static long darkCount(List<DraftGenerator.Item> items) {
        return items.stream().filter(DraftFixer::isDark).count();
    }

    /** i와 j 자리를 바꾸고, i부터 시각을 다시 맞춘다. 각 자리는 원래 그 자리의 시작 시각보다 앞당기지 않는다. */
    static List<DraftGenerator.Item> swapAndReflow(List<DraftGenerator.Item> day, int i, int j) {
        List<DraftGenerator.Item> order = new ArrayList<>(day);
        DraftGenerator.Item a = order.get(i);
        order.set(i, order.get(j));
        order.set(j, a);
        List<DraftGenerator.Item> out = new ArrayList<>(day.subList(0, i));
        LocalTime cursor = i == 0 ? null : out.get(i - 1).end();
        DraftGenerator.Item before = i == 0 ? null : out.get(i - 1);
        for (int k = i; k < order.size(); k++) {
            DraftGenerator.Item it = order.get(k);
            LocalTime slot = day.get(k).start();
            LocalTime s = slot;
            if (before != null) {
                LocalTime earliest = cursor.plusMinutes(travelMinutes(before, it));
                if (earliest.isAfter(s)) {
                    s = earliest;
                }
            }
            LocalTime e = s.plusMinutes(java.time.Duration.between(it.start(), it.end()).toMinutes());
            out.add(with(it, s, e));
            cursor = e;
            before = it;
        }
        return out;
    }

    /** 관광지는 해가 지기 전(18시)에 끝나는 자리에만 되살린다 — 저녁 뒤 오름·해변 방문을 막으려고(#112). */
    private static boolean inDaylight(List<DraftGenerator.Item> trial, DraftGenerator.Item item) {
        if (!daylightOnly(item)) {
            return true;
        }
        return trial.stream().filter(it -> it.placeId().equals(item.placeId())).findFirst()
                .map(it -> !it.end().isAfter(DAYLIGHT_END)).orElse(false);
    }

    /** 사실로 확인되는 제외 이유가 없는지 — 좌표·교통시설·중복만 본다(휴무는 날마다 따로). */
    static boolean restorable(List<List<DraftGenerator.Item>> days, SavedPlace p) {
        if (p == null || p.getLatitude() == null || p.getLongitude() == null) {
            return false;
        }
        if (p.getPlaceName() == null || TRANSIT.matcher(p.getPlaceName().replace(" ", "")).find()) {
            return false;
        }
        double nearest = nearestScheduledKm(days, p);
        // 이미 넣은 곳과 150m 안이면 같은 곳(중복). 30km 넘게 떨어져 있으면 빈 날에 혼자 넣는 것도 하지 않는다 —
        // 빈 날은 이동 구간이 없어 연속 이동 검사로는 못 막는다(테스트에서 64km 떨어진 곳이 빈 날에 들어갔다).
        return nearest * 1000 > DUPLICATE_METERS && (nearest == Double.MAX_VALUE || nearest <= DraftValidator.MAX_HOP_KM);
    }

    private static double nearestScheduledKm(List<List<DraftGenerator.Item>> days, SavedPlace p) {
        if (p == null || p.getLatitude() == null) {
            return Double.MAX_VALUE;
        }
        return days.stream().flatMap(List::stream).filter(DraftFixer::hasCoords)
                .mapToDouble(it -> GeoUtils.haversineKm(it.latitude(), it.longitude(), p.getLatitude(), p.getLongitude()))
                .min().orElse(Double.MAX_VALUE);
    }

    /** 넣은 날이 규칙을 지키는지: 연속 이동 30km 안, 21시 안에 끝남, 영업시간 밖 방문이 원래보다 늘지 않음. */
    private static boolean fits(List<DraftGenerator.Item> trial, List<DraftGenerator.Item> original, Map<Long, SavedPlace> places,
                                LocalDate date) {
        for (int i = 0; i + 1 < trial.size(); i++) {
            if (hasCoords(trial.get(i)) && hasCoords(trial.get(i + 1)) && km(trial.get(i), trial.get(i + 1)) > DraftValidator.MAX_HOP_KM) {
                return false;
            }
        }
        DraftGenerator.Item last = trial.get(trial.size() - 1);
        if (last.end().isAfter(DAY_END) || last.end().isBefore(last.start())) {
            return false;
        }
        return date == null || outsideHours(trial, places, date) <= outsideHours(original, places, date);
    }

    private static int outsideHours(List<DraftGenerator.Item> items, Map<Long, SavedPlace> places, LocalDate date) {
        int n = 0;
        for (DraftGenerator.Item it : items) {
            SavedPlace p = places.get(it.placeId());
            if (p != null && p.getOpeningPeriods() != null
                    && !DraftValidator.withinHours(p.getOpeningPeriods(), date, it.start(), it.end())) {
                n++;
            }
        }
        return n;
    }

    /** 분류별로 머무는 시간을 잡아 새 칸을 만든다(시각은 넣을 때 정한다). 프롬프트의 머무는 시간과 같은 기준. */
    private static DraftGenerator.Item newItem(SavedPlace p) {
        int stay = switch (p.getCategory() == null ? "other" : p.getCategory()) {
            case "attraction" -> 90;
            case "cafe" -> 50;
            default -> 60;
        };
        LocalTime start = LocalTime.of(10, 0);
        return new DraftGenerator.Item(p.getId(), p.getPlaceName(), p.getCategory(), start, start.plusMinutes(stay),
                p.getLatitude(), p.getLongitude());
    }

    /**
     * at 자리에 넣고, 그 앞 장소의 끝 + 이동 시간에 시작한다. 뒤 장소들은 겹치는 만큼만 민다 — 원래 시각보다 앞당기지 않는다.
     * 빈 날이면 10시에 시작한다.
     */
    static List<DraftGenerator.Item> insertAndShift(List<DraftGenerator.Item> day, int at, DraftGenerator.Item item) {
        List<DraftGenerator.Item> out = new ArrayList<>(day.subList(0, at));
        long stay = java.time.Duration.between(item.start(), item.end()).toMinutes();
        LocalTime start;
        if (at == 0) {
            LocalTime next = day.isEmpty() ? LocalTime.of(10, 0) : day.get(0).start();
            start = day.isEmpty() ? next : next.minusMinutes(stay + travelMinutes(item, day.get(0)));
            if (start.isBefore(LocalTime.of(9, 0)) || start.isAfter(next)) {
                start = next; // 9시 전으로 당겨야 하면 첫 칸 시각에 넣고 뒤를 민다
            }
        } else {
            DraftGenerator.Item prev = day.get(at - 1);
            start = prev.end().plusMinutes(travelMinutes(prev, item));
        }
        LocalTime cursor = start.plusMinutes(stay);
        out.add(with(item, start, cursor));
        DraftGenerator.Item before = item;
        for (int i = at; i < day.size(); i++) {
            DraftGenerator.Item it = day.get(i);
            LocalTime earliest = cursor.plusMinutes(travelMinutes(before, it));
            LocalTime s = it.start().isBefore(earliest) ? earliest : it.start();
            LocalTime e = s.plusMinutes(java.time.Duration.between(it.start(), it.end()).toMinutes());
            out.add(with(it, s, e));
            cursor = e;
            before = it;
        }
        return out;
    }

    static final LocalTime BREAKFAST_UNTIL = LocalTime.of(10, 30);

    /** 검증기가 식사 경고를 낼 날(3곳 이상)에서 빈 식사 칸을 그날 식당으로 채운다. 점심 먼저, 그다음 저녁. */
    static List<DraftGenerator.Item> fitMeals(List<DraftGenerator.Item> day, Map<Long, SavedPlace> places, LocalDate date,
                                              int dayNo, List<String> fixes) {
        if (day.size() < 3) {
            return day;
        }
        List<DraftGenerator.Item> cur = day;
        LocalTime[][] windows = {{DraftValidator.LUNCH_FROM, DraftValidator.LUNCH_TO},
                {DraftValidator.DINNER_FROM, DraftValidator.DINNER_TO}};
        String[] names = {"점심", "저녁"};
        for (int w = 0; w < 2; w++) {
            LocalTime from = windows[w][0];
            LocalTime to = windows[w][1];
            if (hasMealIn(cur, from, to)) {
                continue;
            }
            // 저녁은 검증기와 같은 조건 — 그날 일정이 17시 넘어 이어질 때만 채운다.
            if (w == 1 && !cur.get(cur.size() - 1).end().isAfter(DraftValidator.DINNER_FROM)) {
                continue;
            }
            boolean lunchOk = hasMealIn(cur, DraftValidator.LUNCH_FROM, DraftValidator.LUNCH_TO);
            List<DraftGenerator.Item> best = null;
            DraftGenerator.Item moved = null;
            for (DraftGenerator.Item r : cur) {
                if (!movableMeal(r)) {
                    continue;
                }
                List<DraftGenerator.Item> rest = new ArrayList<>(cur);
                rest.remove(r);
                for (int k = 0; k <= rest.size(); k++) {
                    List<DraftGenerator.Item> trial = placeMeal(rest, k, r, from);
                    DraftGenerator.Item placed = trial.get(k);
                    if (!placed.start().isBefore(to) || !mealFits(trial, cur, places, date)
                            || (lunchOk && !hasMealIn(trial, DraftValidator.LUNCH_FROM, DraftValidator.LUNCH_TO))) {
                        continue;
                    }
                    if (best == null || pathKm(trial) < pathKm(best)) {
                        best = trial;
                        moved = placed;
                    }
                }
            }
            if (best != null) {
                cur = best;
                fixes.add(Josa.eulReul(moved.name()) + " " + dayNo + "일차 " + names[w] + " 시간(" + moved.start() + ")으로 옮겼어요.");
            }
        }
        return cur;
    }

    /** 이미 점심·저녁 칸에 있는 식당과 아침 식당은 그대로 둔다. */
    private static boolean movableMeal(DraftGenerator.Item it) {
        return "restaurant".equals(it.category()) && !it.start().isBefore(BREAKFAST_UNTIL)
                && !inWindow(it, DraftValidator.LUNCH_FROM, DraftValidator.LUNCH_TO)
                && !inWindow(it, DraftValidator.DINNER_FROM, DraftValidator.DINNER_TO);
    }

    private static boolean inWindow(DraftGenerator.Item it, LocalTime from, LocalTime to) {
        return !it.start().isBefore(from) && it.start().isBefore(to);
    }

    private static boolean hasMealIn(List<DraftGenerator.Item> items, LocalTime from, LocalTime to) {
        return items.stream().anyMatch(it -> "restaurant".equals(it.category()) && inWindow(it, from, to));
    }

    /**
     * 식당을 k 자리에 넣는다: 앞 장소들은 그대로, 식당은 (앞 장소 끝 + 이동)과 식사 칸 시작 중 늦은 시각에, 뒤 장소들은 겹치는 만큼만 민다.
     */
    static List<DraftGenerator.Item> placeMeal(List<DraftGenerator.Item> rest, int k, DraftGenerator.Item meal, LocalTime from) {
        List<DraftGenerator.Item> out = new ArrayList<>(rest.subList(0, k));
        long stay = java.time.Duration.between(meal.start(), meal.end()).toMinutes();
        LocalTime start = from;
        if (k > 0) {
            LocalTime earliest = rest.get(k - 1).end().plusMinutes(travelMinutes(rest.get(k - 1), meal));
            if (earliest.isAfter(start)) {
                start = earliest;
            }
        }
        LocalTime cursor = start.plusMinutes(stay);
        out.add(with(meal, start, cursor));
        DraftGenerator.Item before = meal;
        for (int i = k; i < rest.size(); i++) {
            DraftGenerator.Item it = rest.get(i);
            LocalTime earliest = cursor.plusMinutes(travelMinutes(before, it));
            LocalTime s = it.start().isBefore(earliest) ? earliest : it.start();
            LocalTime e = s.plusMinutes(java.time.Duration.between(it.start(), it.end()).toMinutes());
            out.add(with(it, s, e));
            cursor = e;
            before = it;
        }
        return out;
    }

    private static boolean mealFits(List<DraftGenerator.Item> trial, List<DraftGenerator.Item> original, Map<Long, SavedPlace> places,
                                    LocalDate date) {
        return fits(trial, original, places, date);
    }

    /** 그 장소가 확실히 여는 날 중 자리가 있고, 그날 장소들과 가장 가까운 날. */
    private static Optional<Integer> bestOpenDay(List<List<DraftGenerator.Item>> days, SavedPlace p, DraftGenerator.Item it,
                                                 LocalDate startDate) {
        Integer best = null;
        double bestKm = Double.MAX_VALUE;
        for (int d = 0; d < days.size(); d++) {
            if (days.get(d).size() >= DraftValidator.DAY_MAX
                    || !OpeningHoursService.isOpenOn(p.getOpeningPeriods(), startDate.plusDays(d)).orElse(false)) {
                continue;
            }
            double km = days.get(d).stream().filter(o -> hasCoords(o) && hasCoords(it))
                    .mapToDouble(o -> km(o, it)).min().orElse(0);
            if (km < bestKm) {
                bestKm = km;
                best = d;
            }
        }
        return Optional.ofNullable(best);
    }

    /** 넣었을 때 늘어나는 이동거리가 가장 작은 자리. */
    private static int insertAt(List<DraftGenerator.Item> items, DraftGenerator.Item it) {
        int best = items.size();
        double bestAdded = Double.MAX_VALUE;
        for (int i = 0; i <= items.size(); i++) {
            List<DraftGenerator.Item> trial = new ArrayList<>(items);
            trial.add(i, it);
            double added = pathKm(trial) - pathKm(items);
            if (added < bestAdded) {
                bestAdded = added;
                best = i;
            }
        }
        return best;
    }

    /**
     * 끼워 넣은 뒤 시각을 다시 매긴다: 첫 장소 시각에서 시작해 각 장소의 원래 머무는 시간을 지키고,
     * 장소 사이에 직선거리 기준 이동 시간(시속 30km, 10분 단위 올림, 최소 10분)을 둔다. 21시를 넘으면 그대로 두고 검증이 알린다.
     */
    static void retime(List<DraftGenerator.Item> items) {
        if (items.isEmpty()) {
            return;
        }
        LocalTime cursor = items.get(0).start();
        for (int i = 0; i < items.size(); i++) {
            DraftGenerator.Item it = items.get(i);
            if (i > 0) {
                cursor = cursor.plusMinutes(travelMinutes(items.get(i - 1), it));
            }
            long stay = java.time.Duration.between(it.start(), it.end()).toMinutes();
            LocalTime end = cursor.plusMinutes(stay);
            if (end.isBefore(cursor)) { // 자정을 넘기면 더 밀지 않는다
                end = LocalTime.of(23, 59);
            }
            items.set(i, with(it, cursor, end));
            cursor = end;
        }
    }

    static long travelMinutes(DraftGenerator.Item a, DraftGenerator.Item b) {
        if (!hasCoords(a) || !hasCoords(b)) {
            return 30;
        }
        long minutes = (long) Math.ceil(km(a, b) / 30.0 * 60 / 10.0) * 10;
        return Math.max(10, minutes);
    }

    /** 식당과 좌표 없는 장소는 제자리에 두고, 나머지 자리를 앞 장소에서 가장 가까운 순으로 채운다. */
    static List<DraftGenerator.Item> reorder(List<DraftGenerator.Item> items) {
        if (items.size() < 3) {
            return items;
        }
        List<DraftGenerator.Item> movable = new ArrayList<>(items.stream().filter(DraftFixer::movable).toList());
        if (movable.size() < 2) {
            return items;
        }
        List<DraftGenerator.Item> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            DraftGenerator.Item slot = items.get(i);
            if (!movable(slot)) {
                out.add(slot);
                continue;
            }
            DraftGenerator.Item prev = out.isEmpty() ? null : out.get(out.size() - 1);
            DraftGenerator.Item pick = movable.get(0);
            if (prev != null && hasCoords(prev)) {
                for (DraftGenerator.Item c : movable) {
                    if (km(prev, c) < km(prev, pick)) {
                        pick = c;
                    }
                }
            }
            movable.remove(pick);
            out.add(with(pick, slot.start(), slot.end()));
        }
        // 1km 넘게 줄 때만 바꾼다 — 거의 같은 동선이면 AI가 정한 순서를 존중한다.
        return pathKm(out) + 1 < pathKm(items) ? out : items;
    }

    private static boolean movable(DraftGenerator.Item it) {
        return hasCoords(it) && !"restaurant".equals(it.category());
    }

    static double pathKm(List<DraftGenerator.Item> items) {
        double sum = 0;
        for (int i = 0; i + 1 < items.size(); i++) {
            if (hasCoords(items.get(i)) && hasCoords(items.get(i + 1))) {
                sum += km(items.get(i), items.get(i + 1));
            }
        }
        return sum;
    }

    private static boolean hasCoords(DraftGenerator.Item it) {
        return it.latitude() != null && it.longitude() != null;
    }

    private static double km(DraftGenerator.Item a, DraftGenerator.Item b) {
        return GeoUtils.haversineKm(a.latitude(), a.longitude(), b.latitude(), b.longitude());
    }

    private static DraftGenerator.Item with(DraftGenerator.Item it, LocalTime start, LocalTime end) {
        return new DraftGenerator.Item(it.placeId(), it.name(), it.category(), start, end, it.latitude(), it.longitude());
    }
}
