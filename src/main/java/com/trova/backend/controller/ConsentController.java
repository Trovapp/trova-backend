package com.trova.backend.controller;

import com.trova.backend.entity.User;
import com.trova.backend.service.ConsentService;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/consents")
public class ConsentController {

    private final CurrentUserService currentUserService;
    private final ConsentService consentService;

    public ConsentController(CurrentUserService currentUserService, ConsentService consentService) {
        this.currentUserService = currentUserService;
        this.consentService = consentService;
    }

    public record ConsentResponse(boolean needed, String termsVersion, String privacyVersion) {
    }

    public record ConsentRequest(String termsVersion, String privacyVersion, Boolean ageOver14) {
    }

    @GetMapping
    public ResponseEntity<ConsentResponse> get(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        return ResponseEntity.ok(new ConsentResponse(
                consentService.needed(user), consentService.getTermsVersion(), consentService.getPrivacyVersion()));
    }

    @PostMapping
    public ResponseEntity<Void> record(Authentication authentication, @RequestBody ConsentRequest request) {
        User user = currentUserService.resolve(authentication);
        consentService.record(user, request.termsVersion(), request.privacyVersion(), request.ageOver14());
        return ResponseEntity.noContent().build();
    }
}
