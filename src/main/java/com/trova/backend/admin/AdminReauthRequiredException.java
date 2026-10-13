package com.trova.backend.admin;

/** 관리자 실행 조치는 로그인(관리자 웹 토큰 발급) 10분 안에만 — 지나면 화면이 다시 로그인을 안내한다(ADR 0014). */
public class AdminReauthRequiredException extends RuntimeException {

    public AdminReauthRequiredException() {
        super("REAUTH_REQUIRED");
    }
}
