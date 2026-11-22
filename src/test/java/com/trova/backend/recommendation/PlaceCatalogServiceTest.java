package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.repository.PlaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaceCatalogServiceTest {

    @Mock
    private PlaceRepository placeRepository;

    @InjectMocks
    private PlaceCatalogService placeCatalogService;

    private GooglePlacesNearbySearchResponse.Place rawPlace(String id, String name) {
        return new GooglePlacesNearbySearchResponse.Place(
                id, new GooglePlacesNearbySearchResponse.Place.DisplayName(name), List.of("cafe"),
                4.5, 100, "PRICE_LEVEL_MODERATE",
                new GooglePlacesNearbySearchResponse.Place.Location(37.5, 127.0), "서울 어딘가");
    }

    @Test
    void 반대_순서의_두_요청도_같은_순서로_쓴다() throws Exception {
        var a = rawPlace("g1", "카페A");
        var b = rawPlace("g2", "카페B");
        var start = new CyclicBarrier(2);
        ThreadLocal<List<String>> writes = ThreadLocal.withInitial(ArrayList::new);
        when(placeRepository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    writes.get().add(invocation.getArgument(0, String.class));
                    return 1;
                });
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Callable<List<String>>> requests = List.of(List.of(a, b), List.of(b, a)).stream()
                    .<Callable<List<String>>>map(input -> () -> {
                        start.await(5, TimeUnit.SECONDS);
                        placeCatalogService.upsertAll(input);
                        return List.copyOf(writes.get());
                    }).toList();
            for (var result : pool.invokeAll(requests, 10, TimeUnit.SECONDS)) {
                assertThat(result.get()).containsExactly("g1", "g2");
            }
        }
    }

    @Test
    void 기존에_있는_장소는_그대로_반환하고_새로_저장하지_않는다() {
        var raw = rawPlace("g1", "카페A");
        Place existing = new Place("g1", "카페A", "cafe", 4.5, 100, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "서울 어딘가");
        when(placeRepository.findByGooglePlaceIdIn(List.of("g1"))).thenReturn(List.of(existing));

        List<Place> result = placeCatalogService.upsertAll(List.of(raw));

        assertThat(result).containsExactly(existing);
        verify(placeRepository, never()).save(any());
    }

    @Test
    void 새_후보는_충돌_없이_저장한_뒤_다시_조회해서_반환한다() {
        var raw = rawPlace("g1", "카페A");
        Place saved = new Place("g1", "카페A", "cafe", 4.5, 100, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "서울 어딘가");
        when(placeRepository.findByGooglePlaceIdIn(List.of("g1"))).thenReturn(List.of(), List.of(saved));
        when(placeRepository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        List<Place> result = placeCatalogService.upsertAll(List.of(raw));

        assertThat(result).containsExactly(saved);
        verify(placeRepository).insertIfAbsent(
                eq("g1"), eq("카페A"), eq("cafe"), eq(4.5), eq(100), eq("PRICE_LEVEL_MODERATE"),
                eq(37.5), eq(127.0), eq("서울 어딘가"), any());
        verify(placeRepository, never()).save(any());
    }

    @Test
    void 한_응답_안에_같은_장소가_두_번_있어도_한_번만_저장하고_입력_순서대로_반환한다() {
        var a = rawPlace("g1", "카페A");
        var b = rawPlace("g2", "카페B");
        Place savedA = new Place("g1", "카페A", "cafe", 4.5, 100, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "서울 어딘가");
        Place savedB = new Place("g2", "카페B", "cafe", 4.5, 100, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "서울 어딘가");
        when(placeRepository.findByGooglePlaceIdIn(List.of("g1", "g2"))).thenReturn(List.of(), List.of(savedB, savedA));
        when(placeRepository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        List<Place> result = placeCatalogService.upsertAll(List.of(a, b, a));

        assertThat(result).containsExactly(savedA, savedB, savedA);
        verify(placeRepository, times(2)).insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
