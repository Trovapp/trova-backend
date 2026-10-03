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
