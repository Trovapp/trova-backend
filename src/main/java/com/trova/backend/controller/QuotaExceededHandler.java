package com.trova.backend.controller;

import com.trova.backend.service.DailyQuotaService.QuotaExceededException;
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
}
