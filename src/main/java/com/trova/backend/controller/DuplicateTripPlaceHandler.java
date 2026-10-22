package com.trova.backend.controller;

import com.trova.backend.service.TripService.DuplicateTripPlaceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 여행에 이미 있는 장소를 담으려 하면 409(#233). 앱은 code로 나눠 보여 준다 —
 * DUPLICATE_SAME_DAY는 막고 안내만, DUPLICATE_OTHER_DAY는 확인을 받아 allowOtherDay로 다시 보낸다.
 */
@RestControllerAdvice
public class DuplicateTripPlaceHandler {

    public record DuplicateTripPlaceResponse(String code, String message, int day, String placeName) {
    }

    @ExceptionHandler(DuplicateTripPlaceException.class)
    public ResponseEntity<DuplicateTripPlaceResponse> handle(DuplicateTripPlaceException e) {
        String code = e.sameDay() ? "DUPLICATE_SAME_DAY" : "DUPLICATE_OTHER_DAY";
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new DuplicateTripPlaceResponse(code, e.getMessage(), e.day(), e.placeName()));
    }
}
