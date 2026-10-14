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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

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
                && request.endpoint().length() <= MAX_ENDPOINT && isKnownPushService(request.endpoint())
                && request.keys() != null
                && notBlank(request.keys().p256dh(), 200) && notBlank(request.keys().auth(), 100);
    }

    /**
     * 서버가 이 주소로 POST를 보내므로 알려진 브라우저 푸시 서버만 받는다(#185 최종 리뷰) — 내부 주소·아무 서버로
     * 요청을 보내게 하거나(SSRF) 느린 서버로 발송 풀을 막는 일을 막는다. 크롬·엣지(FCM), 사파리·아이폰(애플),
     * 파이어폭스(모질라), 옛 엣지(WNS).
     */
    static boolean isKnownPushService(String endpoint) {
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException e) {
            return false;
        }
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("fcm.googleapis.com")
                || host.equals("updates.push.services.mozilla.com")
                || host.endsWith(".push.apple.com")
                || host.endsWith(".notify.windows.com");
    }

    private static boolean notBlank(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }
}
