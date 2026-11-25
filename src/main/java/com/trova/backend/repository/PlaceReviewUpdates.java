package com.trova.backend.repository;

import com.trova.backend.entity.Place;

/** 상세 조회 전에 읽은 detached Place에서 리뷰·전화 값만 저장한다. */
public interface PlaceReviewUpdates {
    void updateReviewCache(Place place);

    void updatePhone(Place place);
}
