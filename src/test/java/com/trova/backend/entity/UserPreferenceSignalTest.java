package com.trova.backend.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserPreferenceSignalTest {

    @Test
    void 생성자로_필드가_채워진다() {
        User user = new User("google", "u1", "테스트", null);
        Place place = new Place("gp1", "카페", "cafe", 4.5, 10, null, 37.5, 127.0, "서울");

        UserPreferenceSignal signal = new UserPreferenceSignal(user, place, SignalType.BOOKMARK);

        assertThat(signal.getUser()).isEqualTo(user);
        assertThat(signal.getPlace()).isEqualTo(place);
        assertThat(signal.getSignalType()).isEqualTo(SignalType.BOOKMARK);
        assertThat(signal.getCreatedAt()).isNotNull();
    }
}
