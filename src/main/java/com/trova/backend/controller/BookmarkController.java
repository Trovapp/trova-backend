package com.trova.backend.controller;

import com.trova.backend.entity.Bookmark;
import com.trova.backend.entity.User;
import com.trova.backend.repository.BookmarkRepository;
import com.trova.backend.service.BookmarkService;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
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

    public record CreateBookmarkRequest(Long placeId) {
    }

    public record BookmarkResponse(
            Long id, Long placeId, String placeName, String googlePlaceId, String mood, String space, String createdAt
    ) {
        static BookmarkResponse from(Bookmark b) {
            return new BookmarkResponse(
                    b.getId(), b.getPlace().getId(), b.getPlace().getName(), b.getPlace().getGooglePlaceId(),
                    b.getPlace().getMood(), b.getPlace().getSpace(), b.getCreatedAt().toString());
        }
    }

    @GetMapping
    public List<BookmarkResponse> list(OAuth2AuthenticationToken authentication) {
        User user = currentUserService.resolve(authentication);
        return bookmarkRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(BookmarkResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<BookmarkResponse> create(
            OAuth2AuthenticationToken authentication, @RequestBody CreateBookmarkRequest request
    ) {
        if (request == null || request.placeId() == null) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        return bookmarkService.addBookmark(user, request.placeId())
                .map(b -> ResponseEntity.ok(BookmarkResponse.from(b)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(OAuth2AuthenticationToken authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        boolean removed = bookmarkService.removeBookmark(user, id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
