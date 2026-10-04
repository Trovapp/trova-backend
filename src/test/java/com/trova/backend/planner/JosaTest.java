package com.trova.backend.planner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JosaTest {

    @Test
    void 받침에_맞춰_조사를_고르고_한글이_아니면_둘_다_적는다() {
        assertThat(Josa.eunNeun("만리향 만두")).isEqualTo("만리향 만두는");
        assertThat(Josa.eunNeun("밀양돼지국밥")).isEqualTo("밀양돼지국밥은");
        assertThat(Josa.eulReul("국수")).isEqualTo("국수를");
        assertThat(Josa.iGa("카페")).isEqualTo("카페가");
        assertThat(Josa.iGa("송악산")).isEqualTo("송악산이");
        assertThat(Josa.eunNeun("Cafe B")).isEqualTo("Cafe B은(는)");
    }
}
