package com.trova.backend.recommendation;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 외부 장소 검색 결과가 우리가 찾던 가게인지 이름으로 확인한다. 좌표만 보고 가장 가까운(또는 첫) 결과를 고르면
 * 같은 건물의 다른 가게가 잡혀 리뷰 요약에 설빙 리뷰가 섞였다(안드로이드 사용성 테스트 2026-11-12, #414).
 * 지점명("서울명동성당점")은 떼고 상호(브랜드)만 비교한다 — 한쪽 이름이 다른 쪽을 포함하면 같은 가게로 본다.
 */
public final class PlaceNameMatch {

    private PlaceNameMatch() {
    }

    public static boolean sameBusiness(String wanted, String found) {
        if (wanted == null || found == null) {
            return true; // 비교할 이름이 없으면 막지 않는다(예전 동작).
        }
        String a = brand(wanted);
        String b = brand(found);
        if (a.isEmpty() || b.isEmpty()) {
            return true;
        }
        return a.contains(b) || b.contains(a);
    }

    private static final Pattern PARENTHESES = Pattern.compile("\\(([^()]*)\\)");
    private static final Set<String> GENERIC = Set.of("카페", "식당", "베이커리", "호텔", "매장");
    // 붙여 쓴 이름에서 상호와 지점의 경계를 추측하지 않고 확인된 표기만 연결한다.
    private static final Map<String, String> CONFIRMED_NAMES = Map.of(
            "교보문고광화문점", "교보문고광화문",
            "명동성당점", "이삭토스트명동성당점",
            "이삭토스트서울명동성당점", "이삭토스트명동성당점");

    private record CatalogName(List<String> words, List<String> qualifiers) {
        static CatalogName parse(String name) {
            String normalized = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
            List<String> qualifiers = PARENTHESES.matcher(normalized).results()
                    .map(result -> result.group(1).strip()).toList();
            List<String> words = Arrays.stream(PARENTHESES.matcher(normalized).replaceAll(" ")
                            .split("[^\\p{L}\\p{N}]+"))
                    .filter(word -> !word.isEmpty()).toList();
            return new CatalogName(words, qualifiers);
        }

        boolean hasCompatibleQualifiers(CatalogName other) {
            if (qualifiers.equals(other.qualifiers)) {
                return true;
            }
            if (!qualifiers.isEmpty() && !other.qualifiers.isEmpty()) {
                return false;
            }
            CatalogName annotated = qualifiers.isEmpty() ? other : this;
            // 괄호를 생략해도 되는 것으로 확인된 표기만 허용한다.
            return annotated.words.equals(List.of("경포호")) && annotated.qualifiers.equals(List.of("鏡浦湖"));
        }
    }

    public static boolean sameCatalogName(String wanted, String found) {
        if (wanted == null || found == null) {
            return false;
        }
        CatalogName left = CatalogName.parse(wanted);
        CatalogName right = CatalogName.parse(found);
        if (left.words.stream().allMatch(GENERIC::contains) || right.words.stream().allMatch(GENERIC::contains) || !left.hasCompatibleQualifiers(right)) {
            return false;
        }
        String fullA = String.join("", left.words);
        String fullB = String.join("", right.words);
        if (CONFIRMED_NAMES.getOrDefault(fullA, fullA).equals(CONFIRMED_NAMES.getOrDefault(fullB, fullB))) {
            return true;
        }
        // 브랜드를 첫 단어로 추측하지 않는다. 마지막 지점 토큰을 제외한 식별 토큰이 모두 일치해야 한다.
        if (left.words.size() < 2 || right.words.size() < 2) {
            return false;
        }
        List<String> coreA = left.words.subList(0, left.words.size() - 1);
        List<String> coreB = right.words.subList(0, right.words.size() - 1);
        if (!coreA.equals(coreB)) {
            return false;
        }
        String lastA = left.words.getLast();
        String lastB = right.words.getLast();
        if (!lastA.endsWith("점") && !lastB.endsWith("점")) {
            return lastA.equals(lastB);
        }
        String branchA = branchName(lastA, coreA);
        String branchB = branchName(lastB, coreB);
        return !branchA.isEmpty() && !branchB.isEmpty() && (branchA.equals(branchB)
                || withoutRegion(branchA).equals(branchB) || withoutRegion(branchB).equals(branchA));
    }

    private static String branchName(String word, List<String> core) {
        return word.replaceFirst(core.equals(List.of("스타벅스")) ? "r?점$" : "점$", "");
    }

    private static String withoutRegion(String branch) {
        return branch.replaceFirst("^(서울|강남|부산|대구|인천|광주|대전|울산|세종|제주)", "");
    }

    /** 공백·기호를 지우고 소문자로, 여러 단어면 "…점"으로 끝나는 지점 단어를 뗀다. */
    static String brand(String name) {
        String[] tokens = name.trim().split("\\s+");
        String joined = tokens.length > 1
                ? Arrays.stream(tokens).filter(t -> !t.endsWith("점")).collect(Collectors.joining())
                : tokens[0];
        if (joined.isEmpty()) {
            joined = String.join("", tokens);
        }
        return joined.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
    }

    /** 검색어로 쓸 상호 — 지점 단어("…점")를 뗀 원래 이름(띄어쓰기 유지). */
    public static String brandQuery(String name) {
        String[] tokens = name.trim().split("\\s+");
        if (tokens.length <= 1) {
            return name.trim();
        }
        String joined = Arrays.stream(tokens).filter(t -> !t.endsWith("점")).collect(Collectors.joining(" "));
        return joined.isEmpty() ? name.trim() : joined;
    }
}
