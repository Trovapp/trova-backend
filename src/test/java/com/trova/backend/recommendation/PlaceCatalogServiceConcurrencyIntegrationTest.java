package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.repository.PlaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 검색 결과를 두 요청이 동시에 카탈로그에 저장해도 둘 다 성공해야 한다(#37).
 * "조회 → 없으면 저장" 사이에 다른 요청이 끼면 늦은 쪽이 유니크 제약(google_place_id)에 걸려
 * 추천·검색·대안 찾기·빈 시간 추천 API가 500이 된다.
 */
@SpringBootTest
class PlaceCatalogServiceConcurrencyIntegrationTest {

    private static final int CANDIDATES = 20;
    private static final int ROUNDS = 5;

    @Autowired private PlaceCatalogService placeCatalogService;
    @Autowired private PlaceRepository placeRepository;

    private final List<String> createdIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        placeRepository.deleteAll(placeRepository.findByGooglePlaceIdIn(createdIds));
    }

    @Test
    void 같은_검색결과를_동시에_저장해도_둘_다_같은_장소로_성공한다() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        for (int round = 0; round < ROUNDS; round++) {
            String prefix = "catalog-race-" + System.nanoTime() + "-";
            List<GooglePlacesNearbySearchResponse.Place> raw = IntStream.range(0, CANDIDATES)
                    .mapToObj(i -> rawPlace(prefix + i))
                    .toList();
            raw.forEach(p -> createdIds.add(p.id()));

            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<Place>>> futures = new ArrayList<>();
            for (int t = 0; t < 2; t++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return placeCatalogService.upsertAll(raw);
                }));
            }
            start.countDown();

            List<Long> first = ids(futures.get(0).get(10, TimeUnit.SECONDS));
            List<Long> second = ids(futures.get(1).get(10, TimeUnit.SECONDS));
            assertThat(first).hasSize(CANDIDATES).doesNotContainNull().isEqualTo(second);
            assertThat(placeRepository.findByGooglePlaceIdIn(raw.stream().map(GooglePlacesNearbySearchResponse.Place::id).toList()))
                    .hasSize(CANDIDATES);
        }
        executor.shutdown();
    }

    private static List<Long> ids(List<Place> places) {
        return places.stream().map(Place::getId).toList();
    }

    private static GooglePlacesNearbySearchResponse.Place rawPlace(String id) {
        return new GooglePlacesNearbySearchResponse.Place(
                id, new GooglePlacesNearbySearchResponse.Place.DisplayName("카페"), List.of("cafe"),
                4.5, 100, "PRICE_LEVEL_MODERATE",
                new GooglePlacesNearbySearchResponse.Place.Location(37.5, 127.0), "서울 어딘가");
    }
}
