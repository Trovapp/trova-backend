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
}
