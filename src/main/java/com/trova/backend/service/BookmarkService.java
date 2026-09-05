package com.trova.backend.service;

import com.trova.backend.entity.Bookmark;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.User;
import com.trova.backend.entity.UserPreference;
import com.trova.backend.repository.BookmarkRepository;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.UserPreferenceRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 추천엔진 결과를 즐겨찾기하면 그 장소의 mood 선호 점수를 올린다(양성 신호만
 * — "추천했는데 안 고름" 음성 신호는 프론트에 추천 화면이 붙기 전까진 없음).
 */
@Service
public class BookmarkService {

    private static final double BOOKMARK_SCORE_DELTA = 1.0;

    private final BookmarkRepository bookmarkRepository;
    private final PlaceRepository placeRepository;
    private final UserPreferenceRepository userPreferenceRepository;

    public BookmarkService(
            BookmarkRepository bookmarkRepository,
            PlaceRepository placeRepository,
            UserPreferenceRepository userPreferenceRepository
    ) {
        this.bookmarkRepository = bookmarkRepository;
        this.placeRepository = placeRepository;
        this.userPreferenceRepository = userPreferenceRepository;
    }

    public Optional<Bookmark> addBookmark(User user, Long placeId) {
        return placeRepository.findById(placeId).map(place -> {
            Optional<Bookmark> existing = bookmarkRepository.findByUserAndPlace(user, place);
            if (existing.isPresent()) {
                return existing.get();
            }

            if (place.getMood() != null) {
                bumpPreference(user, place.getMood());
            }
            return bookmarkRepository.save(new Bookmark(user, place));
        });
    }

    private void bumpPreference(User user, String mood) {
        UserPreference preference = userPreferenceRepository.findByUserAndMood(user, mood)
                .orElseGet(() -> new UserPreference(user, mood, 0.0));
        preference.addScore(BOOKMARK_SCORE_DELTA);
        userPreferenceRepository.save(preference);
    }

    public boolean removeBookmark(User user, Long bookmarkId) {
        return bookmarkRepository.findById(bookmarkId)
                .filter(b -> b.getUser().getId().equals(user.getId()))
                .map(b -> {
                    bookmarkRepository.delete(b);
                    return true;
                })
                .orElse(false);
    }
}
