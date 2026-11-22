package com.trova.backend.recommendation;

import java.util.Arrays;
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

    // 북마크는 특정 지점을 가리킨다. 지점명은 유지하고 한자 병기·공백·기호만 제거한다.
    public static boolean sameCatalogName(String wanted, String found) {
        if (wanted == null || found == null) {
            return false;
        }
        String expected = catalogName(wanted);
        return !expected.isEmpty() && expected.equals(catalogName(found));
    }

    private static String catalogName(String name) {
        return name.replaceAll("[（(]\\s*[\\p{IsHan}\\s]+[）)]", "")
                .replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
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
