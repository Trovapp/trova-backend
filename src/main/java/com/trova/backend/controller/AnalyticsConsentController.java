package com.trova.backend.controller;

import com.trova.backend.service.AnalyticsConsentService;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/analytics-consent")
public class AnalyticsConsentController {
    private final CurrentUserService users;
    private final AnalyticsConsentService consents;

    public AnalyticsConsentController(CurrentUserService users, AnalyticsConsentService consents) {
        this.users = users;
        this.consents = consents;
    }

    @GetMapping
    public ResponseEntity<AnalyticsConsentService.State> get(Authentication auth) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(consents.get(users.resolveUserId(auth)));
    }

    @GetMapping(params = "expectedRevision")
    public ResponseEntity<AnalyticsConsentService.State> checkBatch(Authentication auth,
            @RequestParam long expectedRevision) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(consents.checkBatch(users.resolveUserId(auth), expectedRevision));
    }

    @PutMapping
    public ResponseEntity<AnalyticsConsentService.State> put(Authentication auth,
            @RequestBody AnalyticsConsentService.Selection request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(consents.put(users.resolve(auth).getId(), request));
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(Authentication auth) {
        consents.revoke(users.resolve(auth).getId());
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
}
