package com.trova.backend.controller;

import com.trova.backend.entity.Notification;
import com.trova.backend.entity.User;
import com.trova.backend.repository.NotificationRepository;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final CurrentUserService currentUserService;
    private final NotificationRepository notificationRepository;

    public NotificationController(CurrentUserService currentUserService, NotificationRepository notificationRepository) {
        this.currentUserService = currentUserService;
        this.notificationRepository = notificationRepository;
    }

    public record NotificationResponse(
            Long id, Long tripId, Integer day, String title, String body,
            Double precipitationProb, Long tripPlaceId, String createdAt
    ) {
        static NotificationResponse from(Notification n) {
            return new NotificationResponse(
                    n.getId(), n.getItinerary().getTrip().getId(), n.getItinerary().getDay(),
                    n.getTitle(), n.getBody(), n.getPrecipitationProb(), n.getTripPlaceId(),
                    n.getCreatedAt().toString());
        }
    }

    @GetMapping
    public List<NotificationResponse> list(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        LocalDate today = LocalDate.now();
        // 알림이 가리키는 일정 날짜가 지나면(여행 마지막 날짜가 지난 경우 포함) 사용자가
        // 직접 닫지 않아도 더 이상 보여줄 필요가 없다 — 이미 지난 날씨 경보는 실행 가능한
        // 정보가 아니다.
        return notificationRepository.findByUserAndIsReadFalseOrderByCreatedAtDesc(user).stream()
                .filter(n -> {
                    LocalDate itineraryDate = n.getItinerary().getDate();
                    return itineraryDate == null || !itineraryDate.isBefore(today);
                })
                .map(NotificationResponse::from)
                .toList();
    }

    @PostMapping("/{id}/dismiss")
    public ResponseEntity<Void> dismiss(Authentication authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        return notificationRepository.findById(id)
                .filter(n -> n.getUser().getId().equals(user.getId()))
                .map(n -> {
                    n.markRead();
                    notificationRepository.save(n);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
