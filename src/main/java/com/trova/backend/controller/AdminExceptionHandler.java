package com.trova.backend.controller;

import com.trova.backend.admin.AdminReauthRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class AdminExceptionHandler {

    @ExceptionHandler(AdminReauthRequiredException.class)
    public ResponseEntity<Map<String, String>> reauth() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "REAUTH_REQUIRED"));
    }
}
