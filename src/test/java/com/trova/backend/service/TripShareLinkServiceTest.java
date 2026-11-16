package com.trova.backend.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TripShareLinkServiceTest {

    @Test
    void 웹앱_주소_끝_슬래시는_지우고_비면_상대_경로다() {
        TripShareLinkService absolute = new TripShareLinkService(null, null, "https://app.example.test/");
        TripShareLinkService relative = new TripShareLinkService(null, null, "");

        assertThat(absolute.url("abc")).isEqualTo("https://app.example.test/t/abc");
        assertThat(relative.url("abc")).isEqualTo("/t/abc");
    }
}
