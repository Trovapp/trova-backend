package com.trova.backend.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
class ApiErrorController implements ErrorController {
    @RequestMapping("${spring.web.error.path:${error.path:/error}}")
    ResponseEntity<?> error(HttpServletRequest request) {
        int status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer value ? value : 500;
        HttpStatus known = HttpStatus.resolve(status);
        Object path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of(
                "timestamp", Instant.now(), "path", path == null ? request.getRequestURI() : path,
                "error", known == null ? "Error" : known.getReasonPhrase()));
    }
}
