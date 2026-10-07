package com.trova.backend.controller;

import com.trova.backend.service.DailyQuotaService.QuotaExceededException;
import com.trova.backend.service.PlanService.PlanLimitExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 하루 호출 한도를 넘으면 429 + 사용자에게 보여줄 안내 문구(#33). */
@RestControllerAdvice
public class QuotaExceededHandler {

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<Map<String, String>> handle(QuotaExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("message", e.getMessage()));
    }

    /**
     * 무료·여행 패스 한도를 다 쓰면 402(#130). 앱은 code로 알아보고, 무료였으면(onPass=false) 구매 화면으로 안내한다.
     */
    @ExceptionHandler(PlanLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handle(PlanLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(Map.of(
                "message", e.getMessage(), "code", "PLAN_LIMIT", "feature", e.feature().name(), "onPass", e.onPass()));
    }
}
