package com.trova.backend.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminWebLoginDisabledTest {

    @Test
    void 관리자_웹_주소가_비어_있으면_관리자_웹_로그인은_꺼진다() {
        AdminWebLoginService service = new AdminWebLoginService(null, null, null, null, null, " ");

        assertThat(service.enabled()).isFalse();
    }
}
