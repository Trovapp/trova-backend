package com.trova.backend.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminWebLoginDisabledTest {

    @Test
    void 관리자_웹_주소가_비어_있으면_관리자_웹_로그인은_꺼진다() {
        AdminWebLoginService service = new AdminWebLoginService(null, null, null, null, null, null, " ");

        assertThat(service.enabled()).isFalse();
    }

    @Test
    void 관리자_웹_주소_끝의_슬래시는_한_번만_붙는다() {
        // 정적 사이트(trailingSlash)라 콜백은 /auth/callback/ — 리다이렉트로 ?code=가 떨어질 걱정을 없앤다(#5 리뷰).
        AdminWebLoginService service = new AdminWebLoginService(null, null, null, null, null, null, "https://admin.example.dev/");

        assertThat(service.failureRedirect()).isEqualTo("https://admin.example.dev/auth/callback/?error=oauth_failed");
    }
}
