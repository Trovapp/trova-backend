package com.trova.backend.controller;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Notification;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import com.trova.backend.repository.NotificationRepository;
import com.trova.backend.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationControllerDateTest {

    @Mock private CurrentUserService currentUserService;
    @Mock private NotificationRepository notificationRepository;
    @Mock private Authentication authentication;

    @Test
    void 지난_일정_판단의_오늘은_서버_시간대가_아니라_한국_날짜다() {
        // UTC 9/28 16:00 = 한국 시간 9/29 01:00 — 9/28 일정의 알림은 이미 지난 것이다(#39).
        Clock clock = Clock.fixed(Instant.parse("2026-09-28T16:00:00Z"), ZoneId.of("Asia/Seoul"));
        NotificationController controller = new NotificationController(currentUserService, notificationRepository, clock);
        User user = new User("google", "notif-date", "알림", null);
        Trip trip = new Trip(user, "여행", LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 29));
        Notification yesterday = new Notification(
                user, new Itinerary(trip, 1, LocalDate.of(2026, 9, 28)), "비 소식이 있어요", "본문", 0.8, 1L);
        Notification today = new Notification(
                user, new Itinerary(trip, 2, LocalDate.of(2026, 9, 29)), "비 소식이 있어요", "본문", 0.8, 1L);
        when(currentUserService.resolve(authentication)).thenReturn(user);
        when(notificationRepository.findByUserAndIsReadFalseOrderByCreatedAtDesc(user)).thenReturn(List.of(today, yesterday));

        List<NotificationController.NotificationResponse> result = controller.list(authentication);

        assertThat(result).extracting(NotificationController.NotificationResponse::day).containsExactly(2);
    }
}
