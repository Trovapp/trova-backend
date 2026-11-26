package com.trova.backend.controller;

import com.trova.backend.admin.RequestRateLimiter;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.TripMembershipService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TripMemberController {
    private final TripMembershipService memberships;
    private final CurrentUserService users;
    private final RequestRateLimiter limiter;
    private final int limit;

    public TripMemberController(TripMembershipService memberships, CurrentUserService users, RequestRateLimiter limiter,
            @Value("${app.share-link.public-per-minute:60}") int limit) {
        this.memberships = memberships;
        this.users = users;
        this.limiter = limiter;
        this.limit = limit;
    }

    @PostMapping("/api/public/trips/{token}/join")
    public ResponseEntity<?> join(Authentication auth, @PathVariable String token, HttpServletRequest request) {
        if (!limiter.tryAcquire("public-trip:" + request.getRemoteAddr(), limit))
            return ResponseEntity.status(429).header("Retry-After", "60").build();
        return ResponseEntity.ok(memberships.joinWithResult(token, users.resolve(auth)));
    }

    @ExceptionHandler(TripMembershipService.MemberLimitException.class)
    public ResponseEntity<?> full() {
        return ResponseEntity.status(409).body(Map.of("code", "MEMBER_LIMIT"));
    }

    @GetMapping("/api/trips/{id}/members")
    public List<TripMembershipService.Member> list(Authentication auth, @PathVariable Long id) {
        return memberships.list(id, users.resolve(auth));
    }

    @DeleteMapping("/api/trips/{id}/members/{userId}")
    public ResponseEntity<Void> remove(Authentication auth, @PathVariable Long id, @PathVariable Long userId) {
        memberships.remove(id, userId, users.resolve(auth));
        return ResponseEntity.noContent().build();
    }
}
