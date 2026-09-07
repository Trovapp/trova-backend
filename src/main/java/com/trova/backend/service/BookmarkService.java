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
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 추천엔진 결과를 즐겨찾기하면 그 장소의 mood 선호 점수를 올린다(양성 신호만
 * — "추천했는데 안 고름" 음성 신호는 프론트에 추천 화면이 붙기 전까진 없음).
 * 찜은 폴더(BookmarkFolder)로 정리할 수 있다 — 폴더 없음(null)은 미분류.
 */
@Service
public class BookmarkService {

    private static final double BOOKMARK_SCORE_DELTA = 1.0;

    private final BookmarkRepository bookmarkRepository;
    private final PlaceRepository placeRepository;
    private final UserPreferenceRepository userPreferenceRepository;
    private final BookmarkFolderRepository bookmarkFolderRepository;

    public BookmarkService(
            BookmarkRepository bookmarkRepository,
            PlaceRepository placeRepository,
            UserPreferenceRepository userPreferenceRepository,
            BookmarkFolderRepository bookmarkFolderRepository
    ) {
        this.bookmarkRepository = bookmarkRepository;
        this.placeRepository = placeRepository;
        this.userPreferenceRepository = userPreferenceRepository;
        this.bookmarkFolderRepository = bookmarkFolderRepository;
    }

    public record FolderWithCount(BookmarkFolder folder, long placeCount) {
    }

    public Optional<Bookmark> addBookmark(User user, Long placeId) {
        return addBookmark(user, placeId, null);
    }

    public Optional<Bookmark> addBookmark(User user, Long placeId, Long folderId) {
        return placeRepository.findById(placeId).map(place -> {
            Optional<Bookmark> existing = bookmarkRepository.findByUserAndPlace(user, place);
            if (existing.isPresent()) {
                return existing.get();
            }

            if (place.getMood() != null) {
                bumpPreference(user, place.getMood());
            }
            Bookmark bookmark = bookmarkRepository.save(new Bookmark(user, place));
            if (folderId != null) {
                bookmarkFolderRepository.findByIdAndUser(folderId, user).ifPresent(bookmark::applyFolder);
                bookmark = bookmarkRepository.save(bookmark);
            }
            return bookmark;
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

    public BookmarkFolder createFolder(User user, String name, String color) {
        return bookmarkFolderRepository.save(new BookmarkFolder(user, name, color));
    }

    public List<FolderWithCount> listFolders(User user) {
        return bookmarkFolderRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(folder -> new FolderWithCount(folder, bookmarkRepository.countByFolder(folder)))
                .toList();
    }

    /** 소속 찜은 지우지 않고 미분류(folder=null)로 되돌린 뒤 폴더를 삭제한다. */
    public boolean deleteFolder(User user, Long folderId) {
        return bookmarkFolderRepository.findByIdAndUser(folderId, user)
                .map(folder -> {
                    for (Bookmark member : bookmarkRepository.findByFolder(folder)) {
                        member.applyFolder(null);
                        bookmarkRepository.save(member);
                    }
                    bookmarkFolderRepository.delete(folder);
                    return true;
                })
                .orElse(false);
    }

    /** folderId가 null이면 미분류로 옮긴다. folderId를 줬는데 그 폴더가 없거나
     *  내 폴더가 아니면 빈 Optional을 돌려준다(찜을 엉뚱한 상태로 두지 않기 위해). */
    public Optional<Bookmark> moveToFolder(User user, Long bookmarkId, Long folderId) {
        Optional<Bookmark> bookmarkOpt = bookmarkRepository.findById(bookmarkId)
                .filter(b -> b.getUser().getId().equals(user.getId()));
        if (bookmarkOpt.isEmpty()) {
            return Optional.empty();
        }
        Bookmark bookmark = bookmarkOpt.get();

        if (folderId == null) {
            bookmark.applyFolder(null);
            return Optional.of(bookmarkRepository.save(bookmark));
        }
        return bookmarkFolderRepository.findByIdAndUser(folderId, user)
                .map(folder -> {
                    bookmark.applyFolder(folder);
                    return bookmarkRepository.save(bookmark);
                });
    }
}
