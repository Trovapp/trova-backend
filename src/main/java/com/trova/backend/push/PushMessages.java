package com.trova.backend.push;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** 웹 푸시 문구(설계 2026-10-14 §3). 앱 안 비 알림 문구와 달리 날짜를 사람이 읽는 형식으로 쓴다. */
public final class PushMessages {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M월 d일(E)", Locale.KOREAN);

    private PushMessages() {
    }

    public static PushMessage draftReady(long draftId, int placeCount, int dayCount) {
        String period = dayCount <= 1 ? "당일" : (dayCount - 1) + "박 " + dayCount + "일";
        return new PushMessage(
                "일정 초안이 준비됐어요",
                "영상 속 장소 " + placeCount + "곳으로 " + period + " 일정을 짰어요. 확인하고 여행에 담아 보세요.",
                "draft:" + draftId,
                "draft-" + draftId);
    }

    public static PushMessage rain(String tripTitle, LocalDate date, double pop, String placeName,
                                   long tripId, long tripPlaceId, long itineraryId) {
        return new PushMessage(
                tripTitle + "에 비 소식이 있어요",
                DATE.format(date) + " 강수확률 " + Math.round(pop * 100) + "%예요. "
                        + placeName + " 대신 갈 실내 장소를 볼까요?",
                "trip:" + tripId + ":place:" + tripPlaceId,
                "rain-" + itineraryId);
    }
}
