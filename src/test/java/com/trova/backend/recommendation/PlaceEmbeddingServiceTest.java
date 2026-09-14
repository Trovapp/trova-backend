package com.trova.backend.recommendation;

import com.trova.backend.embedding.GeminiEmbeddingClient;
import com.trova.backend.entity.Place;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaceEmbeddingServiceTest {

    @Mock private GeminiEmbeddingClient geminiEmbeddingClient;
    @Mock private PlaceRepository placeRepository;
    @Mock private ApiCallLogService apiCallLogService;
    @InjectMocks private PlaceEmbeddingService placeEmbeddingService;

    private void setId(Place place, Long id) {
        try {
            var field = Place.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(place, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void 이미_임베딩_있는_장소는_다시_생성하지_않는다() {
        Place place = new Place("gp1", "카페", "cafe", 4.5, 10, null, 37.5, 127.0, "서울");
        setId(place, 1L);
        when(placeRepository.findIdsWithEmbedding(List.of(1L))).thenReturn(List.of(1L));

        placeEmbeddingService.ensureEmbeddings(List.of(place));

        verifyNoInteractions(geminiEmbeddingClient);
    }

    @Test
    void 임베딩_없는_장소는_생성해서_저장한다() {
        Place place = new Place("gp2", "박물관", "museum", 4.0, 5, null, 37.5, 127.0, "서울");
        setId(place, 2L);
        when(placeRepository.findIdsWithEmbedding(List.of(2L))).thenReturn(List.of());
        when(geminiEmbeddingClient.embed(anyString())).thenReturn(Optional.of(new float[]{0.6f, 0.8f}));

        placeEmbeddingService.ensureEmbeddings(List.of(place));

        verify(placeRepository).updateEmbedding(eq(2L), eq("[0.6,0.8]"));
    }

    @Test
    void 생성_실패하면_저장하지_않고_조용히_넘어간다() {
        Place place = new Place("gp3", "공원", "park", null, null, null, 37.5, 127.0, "서울");
        setId(place, 3L);
        when(placeRepository.findIdsWithEmbedding(List.of(3L))).thenReturn(List.of());
        when(geminiEmbeddingClient.embed(anyString())).thenReturn(Optional.empty());

        placeEmbeddingService.ensureEmbeddings(List.of(place));

        verify(placeRepository, never()).updateEmbedding(anyLong(), anyString());
    }

    @Test
    void 임베딩_존재여부_조회가_실패하면_임베딩_생성_자체를_건너뛴다() {
        Place place = new Place("gp4", "미술관", "museum", 4.2, 8, null, 37.5, 127.0, "서울");
        setId(place, 4L);
        when(placeRepository.findIdsWithEmbedding(List.of(4L))).thenThrow(new RuntimeException("pgvector 마이그레이션 미적용"));

        placeEmbeddingService.ensureEmbeddings(List.of(place));

        verifyNoInteractions(geminiEmbeddingClient);
    }
}
