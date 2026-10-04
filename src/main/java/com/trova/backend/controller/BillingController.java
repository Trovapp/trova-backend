package com.trova.backend.controller;

import com.trova.backend.entity.MeteredFeature;
import com.trova.backend.entity.User;
import com.trova.backend.service.BillingService;
import com.trova.backend.service.BillingService.BillingException;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.PlanService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 무료·여행 패스 상태와 구매 기록(#130). */
@RestController
public class BillingController {

    private final CurrentUserService currentUserService;
    private final PlanService planService;
    private final BillingService billingService;

    public BillingController(CurrentUserService currentUserService, PlanService planService, BillingService billingService) {
        this.currentUserService = currentUserService;
        this.planService = planService;
        this.billingService = billingService;
    }

    public record UsageResponse(long used, int limit, long remaining, String period) {
    }

    public record StatusResponse(String plan, LocalDateTime passExpiresAt, boolean enforced,
                                 Map<String, UsageResponse> usage, String productId, String appAccountToken) {
    }

    public record AppleTransactionRequest(String signedTransaction) {
    }

    /** 구매 페이지·남은 횟수 표시용. appAccountToken은 앱이 결제 요청에 넣는다(남의 거래를 못 쓰게). */
    @GetMapping("/api/billing/status")
    public StatusResponse status(Authentication authentication) {
        return toResponse(currentUserService.resolve(authentication));
    }

    @PostMapping("/api/billing/apple-transactions")
    public ResponseEntity<?> recordAppleTransaction(Authentication authentication, @RequestBody AppleTransactionRequest request) {
        if (request == null || request.signedTransaction() == null || request.signedTransaction().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        try {
            billingService.recordAppleTransaction(user, request.signedTransaction());
            return ResponseEntity.ok(toResponse(user));
        } catch (BillingException e) {
            HttpStatus status = switch (e.reason()) {
                case INVALID -> HttpStatus.BAD_REQUEST;
                case FORBIDDEN -> HttpStatus.FORBIDDEN;
                case NOT_SUPPORTED -> HttpStatus.NOT_IMPLEMENTED;
            };
            return ResponseEntity.status(status).body(Map.of("message", e.getMessage()));
        }
    }

    private StatusResponse toResponse(User user) {
        PlanService.Status s = planService.status(user);
        Map<String, UsageResponse> usage = new LinkedHashMap<>();
        for (Map.Entry<MeteredFeature, PlanService.FeatureUsage> e : s.usage().entrySet()) {
            PlanService.FeatureUsage u = e.getValue();
            usage.put(e.getKey().name(), new UsageResponse(u.used(), u.limit(), u.remaining(), u.period().name()));
        }
        return new StatusResponse(s.plan().name(), s.passExpiresAt(), s.enforced(), usage,
                billingService.productId(), BillingService.appAccountToken(user).toString());
    }
}
