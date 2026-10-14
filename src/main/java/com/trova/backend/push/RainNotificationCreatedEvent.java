package com.trova.backend.push;

import java.time.LocalDate;

/** 여행 날 비 알림이 새로 저장됨(#185). */
public record RainNotificationCreatedEvent(Long userId, Long tripId, Long itineraryId, Long tripPlaceId,
                                           String tripTitle, LocalDate date, double pop, String placeName) { }
