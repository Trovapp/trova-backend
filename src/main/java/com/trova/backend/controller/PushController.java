package com.trova.backend.controller;

import com.trova.backend.entity.User;
import com.trova.backend.push.PushSubscriptionService;
import com.trova.backend.push.VapidKeys;
import com.trova.backend.repository.PushSubscriptionRepository;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** PWA 웹 푸시 구독(#185, ADR 0016). 앱은 열 때마다 구독을 다시 보내 끊긴 구독을 되살린다. */
@RestController
public class PushController {

    private static final int MAX_ENDPOINT = 1000;

    public record PublicKeyResponse(String publicKey) { }

    public record Keys(String p256dh, String auth) { }

    public record SubscribeRequest(String endpoint, Keys keys) { }

    public record UnsubscribeRequest(String endpoint) { }

    private final VapidKeys vapidKeys;
    private final PushSubscriptionRepository subscriptions;
    private final PushSubscriptionService subscriptionService;
    private final CurrentUserService currentUserService;

    public PushController(VapidKeys vapidKeys, PushSubscriptionRepository subscriptions,
                          PushSubscriptionService subscriptionService, CurrentUserService currentUserService) {
        this.vapidKeys = vapidKeys;
        this.subscriptions = subscriptions;
        this.subscriptionService = subscriptionService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/push/public-key")
    public ResponseEntity<PublicKeyResponse> publicKey(Authentication authentication) {
        currentUserService.resolve(authentication);
        if (!vapidKeys.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new PublicKeyResponse(vapidKeys.publicKey()));
    }

    @PostMapping("/api/push/subscriptions")
    public ResponseEntity<Void> subscribe(Authentication authentication, @RequestBody SubscribeRequest request) {
        User user = currentUserService.resolve(authentication);
        if (!isValid(request)) {
            return ResponseEntity.badRequest().build();
        }
        subscriptionService.upsert(user.getId(), request.endpoint(), request.keys().p256dh(), request.keys().auth());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/push/subscriptions")
    public ResponseEntity<Void> unsubscribe(Authentication authentication, @RequestBody UnsubscribeRequest request) {
        User user = currentUserService.resolve(authentication);
        if (request != null && request.endpoint() != null) {
            subscriptions.deleteByEndpointAndUserId(request.endpoint(), user.getId());
        }
        return ResponseEntity.noContent().build();
    }

    private static boolean isValid(SubscribeRequest request) {
        return request != null && request.endpoint() != null
                && request.endpoint().startsWith("https://") && request.endpoint().length() <= MAX_ENDPOINT
                && request.keys() != null
                && notBlank(request.keys().p256dh(), 200) && notBlank(request.keys().auth(), 100);
    }

    private static boolean notBlank(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }
}
