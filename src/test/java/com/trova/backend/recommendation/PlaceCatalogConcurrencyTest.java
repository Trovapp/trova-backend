package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.recommendation.GooglePlacesNearbySearchResponse.Place.DisplayName;
import com.trova.backend.recommendation.GooglePlacesNearbySearchResponse.Place.Location;
import com.trova.backend.repository.PlaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PlaceCatalogConcurrencyTest {
    @Autowired
    private PlaceCatalogService catalog;
    @Autowired
    private PlaceRepository places;

    @Test
    void 반대_순서로_동시에_삽입하고_좌표를_복구해도_각_요청의_순서로_반환한다() throws Exception {
        List<String> ids = List.of("order-test-a", "order-test-b", "order-test-c");
        places.save(new Place(ids.get(1), "카페", "cafe", null, null, null, null, null, null));
        List<GooglePlacesNearbySearchResponse.Place> raw = ids.stream()
                .map(id -> new GooglePlacesNearbySearchResponse.Place(id, new DisplayName("카페"), List.of("cafe"),
                        null, null, null, new Location(37.5, 127.0), "서울")).toList();
        CyclicBarrier start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<List<Place>> forward = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return catalog.upsertAll(raw);
            });
            Future<List<Place>> reverse = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return catalog.upsertAll(raw.reversed());
            });
            assertThat(forward.get(20, TimeUnit.SECONDS)).extracting(Place::getGooglePlaceId)
                    .containsExactlyElementsOf(ids);
            assertThat(reverse.get(20, TimeUnit.SECONDS)).extracting(Place::getGooglePlaceId)
                    .containsExactlyElementsOf(ids.reversed());
            assertThat(places.findByGooglePlaceIdIn(ids)).hasSize(3)
                    .allSatisfy(place -> assertThat(place.getLatitude()).isEqualTo(37.5));
        } finally {
            places.deleteAll(places.findByGooglePlaceIdIn(ids));
        }
    }
}
