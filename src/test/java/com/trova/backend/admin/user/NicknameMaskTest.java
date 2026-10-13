package com.trova.backend.admin.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NicknameMaskTest {

    @Test
    void 첫_글자만_남기고_최대_4개까지_가린다() {
        assertThat(NicknameMask.mask("김태형")).isEqualTo("김**");
        assertThat(NicknameMask.mask("가나다라마바사")).isEqualTo("가****");
        assertThat(NicknameMask.mask("A")).isEqualTo("A");
    }

    @Test
    void 비었거나_없으면_없음() {
        assertThat(NicknameMask.mask(null)).isEqualTo("(없음)");
        assertThat(NicknameMask.mask(" ")).isEqualTo("(없음)");
    }

    @Test
    void 이모지도_한_글자로_센다() {
        assertThat(NicknameMask.mask("😀하하")).isEqualTo("😀**");
    }
}
