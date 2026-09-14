package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;

/** 평점*log(리뷰수+1) 기반 기본 점수 — 개인화 부스트 이전의 순수 인기도 점수. */
public final class PlaceScoring {

    private PlaceScoring() {
    }

    public static double baseScore(Place place) {
        double rating = place.getRating() != null ? place.getRating() : 0.0;
        int reviewCount = place.getUserRatingCount() != null ? place.getUserRatingCount() : 0;
        return rating * Math.log(reviewCount + 1);
    }
}
