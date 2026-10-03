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
            List<DraftGenerator.Item> reordered = reorder(days.get(i));
            if (!reordered.equals(days.get(i))) {
                fixes.add((i + 1) + "일차 순서를 가까운 곳 순으로 바꿨어요(" + Math.round(pathKm(days.get(i)))
                        + "km → " + Math.round(pathKm(reordered)) + "km).");
                days.set(i, reordered);
            }
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
                    fixes.add(it.name() + "은 " + (from + 1) + "일차 휴무라 " + (to.get() + 1) + "일차로 옮겼어요.");
                } else {
                    excluded.add(new DraftGenerator.Excluded(it.placeId(), it.name(), "여행 날짜에 문을 여는 날이 없어 뺐어요."));
                    fixes.add(it.name() + "은 여는 날이 없어 뺐어요.");
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
                int at = insertAt(day, item);
                List<DraftGenerator.Item> trial = insertAndShift(day, at, item);
                if (!fits(trial, day, places, date)) {
                    continue;
                }
                double added = pathKm(trial) - pathKm(day);
                if (added < bestAdded) {
                    bestAdded = added;
                    bestDay = d;
                    bestTrial = trial;
                }
            }
            if (bestDay != null) {
                days.set(bestDay, bestTrial);
                excluded.remove(e);
                fixes.add(p.getPlaceName() + "은 " + (bestDay + 1) + "일차에 자리가 있어 다시 넣었어요.");
            }
        }
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
