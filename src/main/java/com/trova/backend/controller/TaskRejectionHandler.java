package com.trova.backend.controller;

import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 비동기 실행기(영상 처리·일정 생성·재구성) 대기열이 가득 차 작업을 거절하면 500 대신 503으로 알린다(#31).
 * 작업 정리는 각 컨트롤러가 거절을 받은 자리에서 한다.
 */
@RestControllerAdvice
public class TaskRejectionHandler {

    @ExceptionHandler(TaskRejectedException.class)
    public ResponseEntity<Map<String, String>> handleRejected(TaskRejectedException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", "지금 처리 요청이 많아요. 잠시 후 다시 시도해주세요."));
    }
}
