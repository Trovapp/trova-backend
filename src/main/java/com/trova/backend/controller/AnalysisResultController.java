package com.trova.backend.controller;

import com.trova.backend.service.AnalysisResultService;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalysisResultController {
    private final CurrentUserService users;
    private final AnalysisResultService results;

    public AnalysisResultController(CurrentUserService users, AnalysisResultService results) {
        this.users = users;
        this.results = results;
    }

    @GetMapping("/api/places/videos/{jobId}/analysis-result")
    public ResponseEntity<AnalysisResultService.Result> get(Authentication auth, @PathVariable Long jobId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(results.get(users.resolve(auth), jobId));
    }
}
