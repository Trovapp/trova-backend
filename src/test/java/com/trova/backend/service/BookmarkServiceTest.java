package com.trova.backend.service;

import com.trova.backend.entity.Bookmark;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.User;
import com.trova.backend.entity.UserPreference;
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
        service = new BookmarkService(bookmarkRepository, placeRepository, userPreferenceRepository);
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
}
