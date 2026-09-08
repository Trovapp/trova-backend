package com.trova.backend.controller;

import com.trova.backend.entity.Bookmark;
import com.trova.backend.entity.User;
import com.trova.backend.repository.BookmarkRepository;
import com.trova.backend.service.BookmarkService;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bookmarks")
public class BookmarkController {

    private final CurrentUserService currentUserService;
    private final BookmarkService bookmarkService;
    private final BookmarkRepository bookmarkRepository;

    public BookmarkController(
            CurrentUserService currentUserService, BookmarkService bookmarkService, BookmarkRepository bookmarkRepository
    ) {
        this.currentUserService = currentUserService;
        this.bookmarkService = bookmarkService;
        this.bookmarkRepository = bookmarkRepository;
    }

    public record CreateBookmarkRequest(Long placeId, Long folderId) {
    }

    public record MoveBookmarkRequest(Long folderId) {
    }

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
                || request.color() == null || request.color().isBlank()) {
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
