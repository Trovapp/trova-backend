package com.trova.backend.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookmarkTest {

    @Test
    void 생성_직후엔_폴더가_없다() {
        User user = new User("google", "bm-test", "테스트유저", null);
        Place place = new Place("g1", "카페A", "cafe", 4.5, 100, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "주소");

        Bookmark bookmark = new Bookmark(user, place);

        assertThat(bookmark.getFolder()).isNull();
    }

    @Test
    void applyFolder로_폴더를_지정하거나_null로_되돌릴_수_있다() {
        User user = new User("google", "bm-test", "테스트유저", null);
        Place place = new Place("g1", "카페A", "cafe", 4.5, 100, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "주소");
        Bookmark bookmark = new Bookmark(user, place);
        BookmarkFolder folder = new BookmarkFolder(user, "카페 모음", "#4A90D9");

        bookmark.applyFolder(folder);
        assertThat(bookmark.getFolder()).isEqualTo(folder);

        bookmark.applyFolder(null);
        assertThat(bookmark.getFolder()).isNull();
    }
}
