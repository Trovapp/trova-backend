package com.trova.backend.push;

/** 자동 일정 초안이 READY로 저장됨(#185). 리스너가 엔티티를 다시 읽지 않도록 문구에 필요한 값을 담는다. */
public record TripDraftReadyEvent(Long userId, Long draftId, int placeCount, int dayCount) { }
