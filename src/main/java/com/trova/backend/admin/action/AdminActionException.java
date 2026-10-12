package com.trova.backend.admin.action;

import org.springframework.http.HttpStatus;

public class AdminActionException extends RuntimeException {

    private final HttpStatus status;

    private AdminActionException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static AdminActionException notFound() {
        return new AdminActionException(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다.");
    }

    /** 화면에 그대로 보일 문구. */
    public static AdminActionException conflict(String message) {
        return new AdminActionException(HttpStatus.CONFLICT, message);
    }

    public static AdminActionException badRequest(String message) {
        return new AdminActionException(HttpStatus.BAD_REQUEST, message);
    }

    public HttpStatus status() {
        return status;
    }
}
