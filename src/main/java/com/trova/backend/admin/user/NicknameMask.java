package com.trova.backend.admin.user;

/** 관리자 화면에 닉네임을 그대로 보이지 않는다 — 첫 글자만(ADR 0014). */
public final class NicknameMask {

    private NicknameMask() {
    }

    public static String mask(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return "(없음)";
        }
        String trimmed = nickname.strip();
        int first = trimmed.codePointAt(0);
        int rest = trimmed.codePointCount(0, trimmed.length()) - 1;
        return new String(Character.toChars(first)) + "*".repeat(Math.min(rest, 4));
    }
}
