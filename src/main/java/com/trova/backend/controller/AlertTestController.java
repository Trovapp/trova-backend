package com.trova.backend.controller;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Hidden
@RestController
@RequestMapping("/api/_test")
class AlertTestController {
    private final boolean enabled;

    AlertTestController(@Value("${SHIP_AND_PING_ALERT_TEST:}") String flag) {
        enabled = "true".equals(flag);
    }

    @GetMapping("/fail")
    void fail() {
        requireEnabled();
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @GetMapping("/slow")
    CompletionStage<ResponseEntity<Void>> slow() {
        requireEnabled();
        return CompletableFuture.supplyAsync(() -> ResponseEntity.ok().build(),
                CompletableFuture.delayedExecutor(6, TimeUnit.SECONDS));
    }

    private void requireEnabled() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
