package com.trova.backend.service;

import com.trova.backend.entity.Bookmark;
import com.trova.backend.entity.BookmarkFolder;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.User;
import com.trova.backend.entity.UserPreference;
import com.trova.backend.repository.BookmarkFolderRepository;
import com.trova.backend.repository.BookmarkRepository;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.UserPreferenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookmarkServiceTest {

    @Mock private BookmarkRepository bookmarkRepository;
    @Mock private PlaceRepository placeRepository;
    @Mock private UserPreferenceRepository userPreferenceRepository;
    @Mock private BookmarkFolderRepository bookmarkFolderRepository;

    private BookmarkService service;

    // 실제로는 항상 영속화된(=id가 있는) User만 다루므로, 소유자 비교 로직을 실제와
    // 같은 조건에서 테스트하려면 id를 채워둬야 한다(순수 Mockito 테스트라 저장은 안 함).
    private final User user;
    {
        user = new User("google", "bookmark-test", "테스트유저", null);
        ReflectionTestUtils.setField(user, "id", 1L);
    }

    private Place place(String mood) {
        Place p = new Place("g1", "카페A", "cafe", 4.5, 100, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "주소");
        if (mood != null) {
            p.applyTags(mood, "INDOOR");
        }
        return p;
    }

    private void setUp() {
        service = new BookmarkService(
                bookmarkRepository, placeRepository, userPreferenceRepository, bookmarkFolderRepository);
    }

    @Test
    void 북마크하면_해당_mood의_선호점수가_1_증가한다_최초() {
        setUp();
        Place place = place("TRENDY");
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(bookmarkRepository.findByUserAndPlace(user, place)).thenReturn(Optional.empty());
        when(bookmarkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userPreferenceRepository.findByUserAndMood(user, "TRENDY")).thenReturn(Optional.empty());

        Bookmark result = service.addBookmark(user, 1L).orElseThrow();

        assertThat(result.getPlace()).isEqualTo(place);
        ArgumentCaptor<UserPreference> captor = ArgumentCaptor.forClass(UserPreference.class);
        verify(userPreferenceRepository).save(captor.capture());
        assertThat(captor.getValue().getMood()).isEqualTo("TRENDY");
        assertThat(captor.getValue().getScore()).isEqualTo(1.0);
    }

    @Test
    void 이미_있는_mood_선호점수에_1을_더한다() {
        setUp();
        Place place = place("CALM");
        UserPreference existing = new UserPreference(user, "CALM", 2.0);
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(bookmarkRepository.findByUserAndPlace(user, place)).thenReturn(Optional.empty());
        when(bookmarkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userPreferenceRepository.findByUserAndMood(user, "CALM")).thenReturn(Optional.of(existing));

        service.addBookmark(user, 1L);

        assertThat(existing.getScore()).isEqualTo(3.0);
        verify(userPreferenceRepository).save(existing);
    }

    @Test
    void 이미_북마크한_장소면_기존_북마크를_그대로_반환하고_점수를_또_올리지_않는다() {
        setUp();
        Place place = place("CALM");
        Bookmark existingBookmark = new Bookmark(user, place);
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(bookmarkRepository.findByUserAndPlace(user, place)).thenReturn(Optional.of(existingBookmark));

        Bookmark result = service.addBookmark(user, 1L).orElseThrow();

        assertThat(result).isEqualTo(existingBookmark);
        verify(userPreferenceRepository, never()).save(any());
        verify(bookmarkRepository, never()).save(any());
    }

    @Test
    void 존재하지_않는_장소면_빈_Optional을_반환한다() {
        setUp();
        when(placeRepository.findById(999L)).thenReturn(Optional.empty());

        Optional<Bookmark> result = service.addBookmark(user, 999L);

        assertThat(result).isEmpty();
    }

    @Test
    void removeBookmark는_소유자_확인_후_삭제한다() {
        setUp();
        Place place = place("CALM");
        Bookmark bookmark = new Bookmark(user, place);
        when(bookmarkRepository.findById(5L)).thenReturn(Optional.of(bookmark));

        boolean removed = service.removeBookmark(user, 5L);

        assertThat(removed).isTrue();
        verify(bookmarkRepository).delete(bookmark);
    }

    @Test
    void createFolder는_유저와_이름과_색상으로_폴더를_만든다() {
        setUp();
        when(bookmarkFolderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BookmarkFolder folder = service.createFolder(user, "카페 모음", "#4A90D9");

        assertThat(folder.getUser()).isEqualTo(user);
        assertThat(folder.getName()).isEqualTo("카페 모음");
        assertThat(folder.getColor()).isEqualTo("#4A90D9");
    }

    @Test
    void listFolders는_폴더별_찜_개수를_같이_돌려준다() {
        setUp();
        BookmarkFolder folder = new BookmarkFolder(user, "카페 모음", "#4A90D9");
        when(bookmarkFolderRepository.findByUserOrderByCreatedAtDesc(user)).thenReturn(java.util.List.of(folder));
        when(bookmarkRepository.countByFolder(folder)).thenReturn(3L);

        var result = service.listFolders(user);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).folder()).isEqualTo(folder);
        assertThat(result.get(0).placeCount()).isEqualTo(3L);
    }

    @Test
    void deleteFolder는_소속_찜을_미분류로_되돌리고_폴더를_지운다() {
        setUp();
        BookmarkFolder folder = new BookmarkFolder(user, "카페 모음", "#4A90D9");
        Place place = place("CALM");
        Bookmark member = new Bookmark(user, place);
        member.applyFolder(folder);
        when(bookmarkFolderRepository.findByIdAndUser(10L, user)).thenReturn(Optional.of(folder));
        when(bookmarkRepository.findByFolder(folder)).thenReturn(java.util.List.of(member));

        boolean deleted = service.deleteFolder(user, 10L);

        assertThat(deleted).isTrue();
        assertThat(member.getFolder()).isNull();
        verify(bookmarkRepository).save(member);
        verify(bookmarkFolderRepository).delete(folder);
    }

    @Test
    void deleteFolder는_소유자가_아니면_false를_돌려준다() {
        setUp();
        when(bookmarkFolderRepository.findByIdAndUser(10L, user)).thenReturn(Optional.empty());

        boolean deleted = service.deleteFolder(user, 10L);

        assertThat(deleted).isFalse();
    }

    @Test
    void moveToFolder는_찜을_다른_폴더로_옮긴다() {
        setUp();
        Place place = place("CALM");
        Bookmark bookmark = new Bookmark(user, place);
        BookmarkFolder folder = new BookmarkFolder(user, "카페 모음", "#4A90D9");
        when(bookmarkRepository.findById(5L)).thenReturn(Optional.of(bookmark));
        when(bookmarkFolderRepository.findByIdAndUser(10L, user)).thenReturn(Optional.of(folder));
        when(bookmarkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Bookmark result = service.moveToFolder(user, 5L, 10L).orElseThrow();

        assertThat(result.getFolder()).isEqualTo(folder);
    }

    @Test
    void moveToFolder에_null을_주면_미분류로_옮긴다() {
        setUp();
        Place place = place("CALM");
        Bookmark bookmark = new Bookmark(user, place);
        BookmarkFolder folder = new BookmarkFolder(user, "카페 모음", "#4A90D9");
        bookmark.applyFolder(folder);
        when(bookmarkRepository.findById(5L)).thenReturn(Optional.of(bookmark));
        when(bookmarkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Bookmark result = service.moveToFolder(user, 5L, null).orElseThrow();

        assertThat(result.getFolder()).isNull();
    }

    @Test
    void moveToFolder는_존재하지_않는_폴더면_빈_Optional을_돌려준다() {
        setUp();
        Place place = place("CALM");
        Bookmark bookmark = new Bookmark(user, place);
        when(bookmarkRepository.findById(5L)).thenReturn(Optional.of(bookmark));
        when(bookmarkFolderRepository.findByIdAndUser(999L, user)).thenReturn(Optional.empty());

        Optional<Bookmark> result = service.moveToFolder(user, 5L, 999L);

        assertThat(result).isEmpty();
    }

    @Test
    void addBookmark에_folderId를_주면_그_폴더에_바로_배정된다() {
        setUp();
        Place place = place("TRENDY");
        BookmarkFolder folder = new BookmarkFolder(user, "카페 모음", "#4A90D9");
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(bookmarkRepository.findByUserAndPlace(user, place)).thenReturn(Optional.empty());
        when(bookmarkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userPreferenceRepository.findByUserAndMood(user, "TRENDY")).thenReturn(Optional.empty());
        when(bookmarkFolderRepository.findByIdAndUser(10L, user)).thenReturn(Optional.of(folder));

        Bookmark result = service.addBookmark(user, 1L, 10L).orElseThrow();

        assertThat(result.getFolder()).isEqualTo(folder);
    }
}
