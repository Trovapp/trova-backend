package com.trova.backend.controller;

import com.trova.backend.entity.Bookmark;
import com.trova.backend.entity.User;
import com.trova.backend.repository.BookmarkRepository;
import com.trova.backend.service.BookmarkService;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.TripService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/bookmarks")
public class BookmarkController {

    private final CurrentUserService currentUserService;
    private final BookmarkService bookmarkService;
    private final BookmarkRepository bookmarkRepository;
    private final TripService tripService;

    public BookmarkController(
            CurrentUserService currentUserService, BookmarkService bookmarkService, BookmarkRepository bookmarkRepository,
            TripService tripService
    ) {
        this.currentUserService = currentUserService;
        this.bookmarkService = bookmarkService;
        this.bookmarkRepository = bookmarkRepository;
        this.tripService = tripService;
    }

    public record CreateSavedPlaceBookmarkRequest(Long savedPlaceId, Long folderId) {
    }

    /**
     * 영상에서 찾은 장소를 찜한다(#125). 찜은 구글 장소(카탈로그)를 가리키므로 먼저 연결한다.
     * 본인 영상 장소가 아니면 404, 지도에서 찾지 못하면 422(앱이 "지도에서 찾지 못했다"고 알린다).
     */
    @PostMapping("/saved-place")
    public ResponseEntity<BookmarkResponse> createFromSavedPlace(
            Authentication authentication, @RequestBody CreateSavedPlaceBookmarkRequest request
    ) {
        if (request == null || request.savedPlaceId() == null) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        Optional<TripService.SavedPlaceMatch> match = tripService.resolveSavedPlace(user, request.savedPlaceId());
        if (match.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (match.get().place().isEmpty()) {
            return ResponseEntity.unprocessableEntity().build();
        }
        return bookmarkService.addBookmark(user, match.get().place().get().getId(), request.folderId())
                .map(b -> ResponseEntity.ok(BookmarkResponse.from(b)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CreateBookmarkRequest(Long placeId, Long folderId) {
    }

    public record MoveBookmarkRequest(Long folderId) {
    }

    // 앱(FOLDER_NAME_MAX_LENGTH)과 같은 제한 + 색은 #RRGGBB만 허용(#23).
    static final int FOLDER_NAME_MAX_LENGTH = 20;
    private static final java.util.regex.Pattern COLOR_PATTERN = java.util.regex.Pattern.compile("^#[0-9A-Fa-f]{6}$");

    public record CreateFolderRequest(String name, String color) {
    }

    public record BookmarkResponse(
            Long id, Long placeId, String placeName, String googlePlaceId, String mood, String space,
            Double latitude, Double longitude, String createdAt, Long folderId, String category, String address
    ) {
        static BookmarkResponse from(Bookmark b) {
            return new BookmarkResponse(
                    b.getId(), b.getPlace().getId(), b.getPlace().getName(), b.getPlace().getGooglePlaceId(),
                    b.getPlace().getMood(), b.getPlace().getSpace(),
                    b.getPlace().getLatitude(), b.getPlace().getLongitude(), b.getCreatedAt().toString(),
                    b.getFolder() != null ? b.getFolder().getId() : null,
                    b.getPlace().getCategory(), b.getPlace().getAddress());
        }
    }

    public record BookmarkFolderResponse(Long id, String name, String color, long placeCount) {
        static BookmarkFolderResponse from(BookmarkService.FolderWithCount fw) {
            return new BookmarkFolderResponse(
                    fw.folder().getId(), fw.folder().getName(), fw.folder().getColor(), fw.placeCount());
        }
    }

    @GetMapping
    public List<BookmarkResponse> list(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        return bookmarkRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(BookmarkResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<BookmarkResponse> create(
            Authentication authentication, @RequestBody CreateBookmarkRequest request
    ) {
        if (request == null || request.placeId() == null) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        return bookmarkService.addBookmark(user, request.placeId(), request.folderId())
                .map(b -> ResponseEntity.ok(BookmarkResponse.from(b)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        boolean removed = bookmarkService.removeBookmark(user, id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PatchMapping("/{id}")
    public ResponseEntity<BookmarkResponse> move(
            Authentication authentication, @PathVariable Long id, @RequestBody MoveBookmarkRequest request
    ) {
        User user = currentUserService.resolve(authentication);
        return bookmarkService.moveToFolder(user, id, request == null ? null : request.folderId())
                .map(b -> ResponseEntity.ok(BookmarkResponse.from(b)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/folders")
    public List<BookmarkFolderResponse> listFolders(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        return bookmarkService.listFolders(user).stream().map(BookmarkFolderResponse::from).toList();
    }

    @PostMapping("/folders")
    public ResponseEntity<BookmarkFolderResponse> createFolder(
            Authentication authentication, @RequestBody CreateFolderRequest request
    ) {
        if (request == null || request.name() == null || request.name().isBlank()
                || request.name().trim().length() > FOLDER_NAME_MAX_LENGTH
                || request.color() == null || !COLOR_PATTERN.matcher(request.color()).matches()) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        var folder = bookmarkService.createFolder(user, request.name(), request.color());
        return ResponseEntity.ok(BookmarkFolderResponse.from(new BookmarkService.FolderWithCount(folder, 0)));
    }

    @DeleteMapping("/folders/{id}")
    public ResponseEntity<Void> deleteFolder(Authentication authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        boolean removed = bookmarkService.deleteFolder(user, id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
