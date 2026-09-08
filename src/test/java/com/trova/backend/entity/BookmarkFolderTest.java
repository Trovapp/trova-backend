package com.trova.backend.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BookmarkFolderTest {

    @Test
    void 생성하면_이름과_색상과_생성시각이_채워진다() {
        User user = new User("google", "folder-test", "테스트유저", null);

        BookmarkFolder folder = new BookmarkFolder(user, "카페 모음", "#4A90D9");

        assertThat(folder.getUser()).isEqualTo(user);
        assertThat(folder.getName()).isEqualTo("카페 모음");
        assertThat(folder.getColor()).isEqualTo("#4A90D9");
        assertThat(folder.getCreatedAt()).isBeforeOrEqualTo(LocalDateTime.now());
    }
}
