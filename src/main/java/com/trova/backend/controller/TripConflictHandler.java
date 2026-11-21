package com.trova.backend.controller;

import com.trova.backend.service.TripChanges;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {TripController.class, TripMemberController.class, TripShareLinkController.class})
public class TripConflictHandler {
    private final TripController trips;
    public TripConflictHandler(TripController trips) { this.trips = trips; }

    public record Conflict(String code, TripController.TripDetailResponse latest) {}

    @ExceptionHandler({TripChanges.VersionConflict.class, OptimisticLockingFailureException.class})
    public ResponseEntity<?> conflict(HttpServletRequest request, Authentication authentication) {
        Long id = (Long) request.getAttribute(TripChanges.CONFLICT_TRIP);
        if (id == null) return ResponseEntity.status(409).body(new Conflict("VERSION_CONFLICT", null));
        ResponseEntity<TripController.TripDetailResponse> latest = trips.getTrip(authentication, id);
        if (!latest.getStatusCode().is2xxSuccessful()) return latest;
        return ResponseEntity.status(409).body(new Conflict("VERSION_CONFLICT", latest.getBody()));
    }
}
