package com.trova.backend.recommendation;

import com.trova.backend.embedding.GeminiEmbeddingClient;
import com.trova.backend.entity.Place;
import com.trova.backend.repository.PlaceEmbeddingJdbcRepository;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaceEmbeddingServiceTest {

    @Mock private GeminiEmbeddingClient geminiEmbeddingClient;
    @Mock private PlaceRepository placeRepository;
    @Mock private PlaceEmbeddingJdbcRepository placeEmbeddingJdbcRepository;
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
        when(geminiEmbeddingClient.embedBatch(anyList())).thenReturn(Optional.of(List.of(new float[]{0.6f, 0.8f})));

        placeEmbeddingService.ensureEmbeddings(List.of(place));

        verify(placeEmbeddingJdbcRepository).updateEmbeddings(Map.of(2L, "[0.6,0.8]"));
    }

    @Test
    void 생성_실패하면_저장하지_않고_조용히_넘어간다() {
        Place place = new Place("gp3", "공원", "park", null, null, null, 37.5, 127.0, "서울");
        setId(place, 3L);
        when(placeRepository.findIdsWithEmbedding(List.of(3L))).thenReturn(List.of());
        when(geminiEmbeddingClient.embedBatch(anyList())).thenReturn(Optional.empty());

        placeEmbeddingService.ensureEmbeddings(List.of(place));

        verify(placeEmbeddingJdbcRepository, never()).updateEmbeddings(anyMap());
    }

    @Test
    void 임베딩_존재여부_조회가_실패하면_임베딩_생성_자체를_건너뛴다() {
        Place place = new Place("gp4", "미술관", "museum", 4.2, 8, null, 37.5, 127.0, "서울");
        setId(place, 4L);
        when(placeRepository.findIdsWithEmbedding(List.of(4L))).thenThrow(new RuntimeException("pgvector 마이그레이션 미적용"));

        placeEmbeddingService.ensureEmbeddings(List.of(place));

        verifyNoInteractions(geminiEmbeddingClient);
    }

    private Place place(long id, String name) {
        Place place = new Place("gp-" + id, name, "cafe", 4.0, 1, null, 37.5, 127.0, "서울");
        setId(place, id);
        return place;
    }

    @Test
    void 새_장소_여러_개는_묶음_요청_한_번으로_만들고_장소마다_저장한다() {
        List<Place> places = List.of(place(11, "가"), place(12, "나"), place(13, "다"));
        when(placeRepository.findIdsWithEmbedding(List.of(11L, 12L, 13L))).thenReturn(List.of());
        when(geminiEmbeddingClient.embedBatch(anyList())).thenReturn(Optional.of(List.of(
                new float[]{1f}, new float[]{0.5f}, new float[]{0.25f})));

        placeEmbeddingService.ensureEmbeddings(places);

        verify(geminiEmbeddingClient, times(1)).embedBatch(List.of("가 cafe", "나 cafe", "다 cafe"));
        verify(geminiEmbeddingClient, never()).embed(anyString());
        // 장소마다 UPDATE를 따로 보내지 않고 묶음 전송 한 번으로 저장한다(#79).
        verify(placeEmbeddingJdbcRepository, times(1)).updateEmbeddings(Map.of(11L, "[1.0]", 12L, "[0.5]", 13L, "[0.25]"));
        verify(placeRepository, never()).updateEmbedding(anyLong(), anyString());
        // 무료 한도는 문장 수로 세므로 호출 기록도 문장 수(3줄)만큼 남기되, 묶음 INSERT 한 번으로 보낸다.
        verify(apiCallLogService, times(1)).recordBatch(eq("gemini"), eq("place-embedding-batch"), anyLong(),
                eq(true), isNull(), eq(3));
        verify(apiCallLogService, never()).record(any(), any(), any(), anyLong(), anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void 이미_임베딩_있는_장소는_묶음에서_뺀다() {
        List<Place> places = List.of(place(21, "가"), place(22, "나"));
        when(placeRepository.findIdsWithEmbedding(List.of(21L, 22L))).thenReturn(List.of(21L));
        when(geminiEmbeddingClient.embedBatch(anyList())).thenReturn(Optional.of(List.of(new float[]{1f})));

        placeEmbeddingService.ensureEmbeddings(places);

        verify(geminiEmbeddingClient).embedBatch(List.of("나 cafe"));
        verify(placeEmbeddingJdbcRepository).updateEmbeddings(Map.of(22L, "[1.0]"));
    }

    @Test
    void 백_개가_넘으면_백_개씩_나눠_요청한다() {
        List<Place> places = new java.util.ArrayList<>();
        for (long id = 1; id <= 101; id++) {
            places.add(place(id, "장소" + id));
        }
        when(placeRepository.findIdsWithEmbedding(anyList())).thenReturn(List.of());
        when(geminiEmbeddingClient.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return Optional.of(texts.stream().map(t -> new float[]{1f}).toList());
        });

        placeEmbeddingService.ensureEmbeddings(places);

        verify(geminiEmbeddingClient).embedBatch(argThat(texts -> texts.size() == 100));
        verify(geminiEmbeddingClient).embedBatch(argThat(texts -> texts.size() == 1));
        verify(placeEmbeddingJdbcRepository).updateEmbeddings(argThat(m -> m.size() == 100));
        verify(placeEmbeddingJdbcRepository).updateEmbeddings(argThat(m -> m.size() == 1));
    }

    @Test
    void 묶음_저장이_실패해도_예외_없이_끝난다() {
        // 묶음 전송은 한 건이 실패하면 전체가 실패할 수 있다 — 저장 안 된 장소는 다음 검색 때 다시 대상이 된다(#79에서 받아들인 트레이드오프).
        List<Place> places = List.of(place(31, "가"), place(32, "나"));
        when(placeRepository.findIdsWithEmbedding(List.of(31L, 32L))).thenReturn(List.of());
        when(geminiEmbeddingClient.embedBatch(anyList())).thenReturn(Optional.of(List.of(new float[]{1f}, new float[]{1f})));
        doThrow(new RuntimeException("DB 오류")).when(placeEmbeddingJdbcRepository).updateEmbeddings(anyMap());

        placeEmbeddingService.ensureEmbeddings(places);

        verify(placeEmbeddingJdbcRepository).updateEmbeddings(anyMap());
    }

    @Test
    void 묶음_요청이_실패하면_아무것도_저장하지_않고_실패로_기록한다() {
        List<Place> places = List.of(place(41, "가"), place(42, "나"));
        when(placeRepository.findIdsWithEmbedding(List.of(41L, 42L))).thenReturn(List.of());
        when(geminiEmbeddingClient.embedBatch(anyList())).thenReturn(Optional.empty());

        placeEmbeddingService.ensureEmbeddings(places);

        verify(placeEmbeddingJdbcRepository, never()).updateEmbeddings(anyMap());
        verify(apiCallLogService, times(1)).recordBatch(eq("gemini"), eq("place-embedding-batch"), anyLong(),
                eq(false), eq("embedding generation failed"), eq(2));
    }
}
