package com.trova.backend.service;

import com.trova.backend.entity.Bookmark;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.repository.BookmarkRepository;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.UserPreferenceSignalRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 같은 장소 찜 요청이 동시에 들어와도(연타, 네트워크 재전송) 둘 다 성공해야 한다(#35).
 * "조회 → 없으면 저장" 사이에 다른 요청이 끼면 늦은 쪽이 유니크 제약(user_id, place_id)에 걸리는데,
 * 이걸 500으로 흘리면 찜은 저장됐는데 앱엔 실패로 보인다.
 * 트랜잭션 경계를 실제와 같게 보려고 클래스 레벨 @Transactional을 붙이지 않는다.
 */
@SpringBootTest
class BookmarkServiceConcurrencyIntegrationTest {

    @Autowired private BookmarkService bookmarkService;
    @Autowired private UserRepository userRepository;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private BookmarkRepository bookmarkRepository;
    @Autowired private UserPreferenceSignalRepository userPreferenceSignalRepository;
    @MockitoBean private PlaceEmbeddingService placeEmbeddingService;

    private User user;
    private Place place;

    @AfterEach
    void tearDown() {
        if (user != null) {
            bookmarkRepository.findByUserAndPlace(user, place).ifPresent(bookmarkRepository::delete);
            // 찜하면 같이 저장되는 취향 신호가 장소·사용자를 참조하므로 먼저 지운다.
            userPreferenceSignalRepository.deleteAll(userPreferenceSignalRepository.findAll().stream()
                    .filter(signal -> signal.getUser().getId().equals(user.getId()))
                    .toList());
            placeRepository.delete(place);
            userRepository.delete(user);
        }
    }

    @Test
    void 같은_장소_찜이_동시에_들어와도_둘_다_같은_찜으로_성공한다() throws Exception {
        // 임베딩 생성(Gemini 호출) 지연을 흉내 — 이 동안 첫 요청이 아직 끝나지 않은 상태가 된다.
        doAnswer(inv -> {
            Thread.sleep(300);
            return null;
        }).when(placeEmbeddingService).ensureEmbeddings(any());
        user = userRepository.save(new User("google", "bookmark-race-" + System.nanoTime(), "경쟁", null));
        place = placeRepository.save(new Place(
                "bookmark-race-" + System.nanoTime(), "카페", "cafe", null, null, null, 37.5, 127.0, null));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<Bookmark>>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                return bookmarkService.addBookmark(user, place.getId(), null);
            }));
        }
        start.countDown();

        List<Long> bookmarkIds = new ArrayList<>();
        for (Future<Optional<Bookmark>> future : futures) {
            bookmarkIds.add(future.get(10, TimeUnit.SECONDS).orElseThrow().getId());
        }
        executor.shutdown();

        assertThat(bookmarkIds.get(0)).isEqualTo(bookmarkIds.get(1));
        assertThat(bookmarkRepository.findByUserAndPlace(user, place)).isPresent();
    }
}
