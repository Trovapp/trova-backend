package com.trova.backend.controller;

import com.trova.backend.entity.Notification;
import com.trova.backend.entity.NotificationAlternative;
import com.trova.backend.entity.User;
import com.trova.backend.repository.NotificationRepository;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

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

    public record AlternativeResponse(String name, String address, Double latitude, Double longitude) {
        static AlternativeResponse from(NotificationAlternative alt) {
            return new AlternativeResponse(alt.getName(), alt.getAddress(), alt.getLatitude(), alt.getLongitude());
        }
    }

    public record NotificationResponse(
            Long id, Long tripId, Integer day, String title, String body,
            Double precipitationProb, List<AlternativeResponse> alternatives, String createdAt
    ) {
        static NotificationResponse from(Notification n) {
            return new NotificationResponse(
                    n.getId(), n.getItinerary().getTrip().getId(), n.getItinerary().getDay(),
                    n.getTitle(), n.getBody(), n.getPrecipitationProb(),
                    n.getAlternatives().stream().map(AlternativeResponse::from).toList(),
                    n.getCreatedAt().toString());
        }
    }

    @GetMapping
    public List<NotificationResponse> list(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        return notificationRepository.findByUserAndIsReadFalseOrderByCreatedAtDesc(user).stream()
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
