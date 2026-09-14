package com.trova.backend.recommendation;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleTypeMapperTest {

    @Test
    void 매핑된_카테고리는_구글_타입을_반환한다() {
        assertThat(GoogleTypeMapper.toGoogleType("카페")).isEqualTo(Optional.of("cafe"));
        assertThat(GoogleTypeMapper.toGoogleType("맛집")).isEqualTo(Optional.of("restaurant"));
        assertThat(GoogleTypeMapper.toGoogleType("박물관")).isEqualTo(Optional.of("museum"));
    }

    @Test
    void 매핑에_없는_카테고리는_빈값을_반환한다() {
        assertThat(GoogleTypeMapper.toGoogleType("아무말대잔치")).isEmpty();
    }

    @Test
    void null이나_공백은_빈값을_반환한다() {
        assertThat(GoogleTypeMapper.toGoogleType(null)).isEmpty();
        assertThat(GoogleTypeMapper.toGoogleType("  ")).isEmpty();
    }
}
