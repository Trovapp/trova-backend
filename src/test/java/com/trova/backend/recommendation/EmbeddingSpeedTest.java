package com.trova.backend.recommendation;

import com.trova.backend.congestion.SeoulCongestionApiClient;
import com.trova.backend.congestion.SeoulCongestionResponse;
import com.trova.backend.embedding.GeminiEmbeddingClient;
import com.trova.backend.embedding.GeminiTextClient;
import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.repository.PlaceEmbeddingJdbcRepository;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.UserPreferenceSignalRepository;
import com.trova.backend.service.ApiCallLogService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingSpeedTest {
    private static void id(Object entity, long id) { ReflectionTestUtils.setField(entity, "id", id); }
    private static void delay(long ms) throws InterruptedException { if (ms > 0) Thread.sleep(ms); }

    private record Run(List<AlternativeCandidate> candidates, Map<Long, Double> scores,
                       Map<Long, String> vectors, int calls, List<List<String>> texts, long millis) {}

    /** 실제 임베딩·개인화·대안 서비스, I/O만 가짜. baseline 클래스는 재현 스크립트가 main 원문으로 생성한다. */
    @SuppressWarnings("unchecked")
    private Run run(boolean baseline, boolean congestion, boolean explanations, long embeddingDelay,
                    long congestionDelay, long scoreDelay) throws Exception {
        return run(baseline, congestion, explanations, embeddingDelay, congestionDelay, scoreDelay, false, false);
    }

    @SuppressWarnings("unchecked")
    private Run run(boolean baseline, boolean congestion, boolean explanations, long embeddingDelay,
                    long congestionDelay, long scoreDelay, boolean awaitCongestion, boolean outsideDistance) throws Exception {
        CountDownLatch congestionCalled = new CountDownLatch(1);
        CountDownLatch embeddingStarted = new CountDownLatch(1);
        Thread requestThread = Thread.currentThread();
        GooglePlacesApiClient google = mock(GooglePlacesApiClient.class);
        PlaceCatalogService catalog = mock(PlaceCatalogService.class);
        TripPlaceRepository trips = mock(TripPlaceRepository.class);
        PlaceRepository repository = mock(PlaceRepository.class);
        PlaceEmbeddingJdbcRepository jdbc = mock(PlaceEmbeddingJdbcRepository.class);
        UserPreferenceSignalRepository signals = mock(UserPreferenceSignalRepository.class);
        ApiCallLogService apiLog = mock(ApiCallLogService.class);
        GeminiTextClient text = mock(GeminiTextClient.class);
        SeoulCongestionApiClient crowd = mock(SeoulCongestionApiClient.class);
        Map<Long, String> vectors = new LinkedHashMap<Long, String>();
        vectors.put(12L, "[0.3]"); // 기존·신규가 섞인 요청
        List<List<String>> submitted = new ArrayList<List<String>>();
        AtomicInteger calls = new AtomicInteger();
        GeminiEmbeddingClient fakeGemini = new GeminiEmbeddingClient() {
            public Optional<float[]> embed(String ignored) { throw new AssertionError("단건 호출 금지"); }
            public Optional<List<float[]>> embedBatch(List<String> texts) {
                calls.incrementAndGet();
                embeddingStarted.countDown();
                if (awaitCongestion) {
                    try {
                        assertTrue(congestionCalled.await(5, TimeUnit.SECONDS), "혼잡도 조회가 임베딩 HTTP 대기 중 실행되어야 한다");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                synchronized (submitted) { submitted.add(List.copyOf(texts)); }
                try { delay(embeddingDelay); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return Optional.of(texts.stream().map(t -> new float[]{t.startsWith("신규") ? .9f : .1f}).toList());
            }
        };
        User user = new User("fake", "U", "테스트", null); id(user, 1);
        Itinerary itinerary = new Itinerary(new Trip(user, "여행", null, null), 1, null);
        TripPlace target = new TripPlace(itinerary, "원본", null, "cafe", 37.0, 127.0,
                null, null, 1, PlaceSource.NORMAL, null); id(target, 1);
        List<Place> places = List.of(
                new Place("a", "신규", "cafe", 4.0, 10, null, 37.001, 127.0, "주소"),
                new Place("b", congestion ? "경복궁" : "기존", "cafe", 4.0, 10, null, outsideDistance ? 38.0 : 37.001, 127.0, "주소"),
                new Place("c", "거리탈락_선호신호", "cafe", 5.0, 5000, null, 38.0, 127.0, "주소"),
                new Place("d", "좌표없음", "cafe", 5.0, 5000, null, null, null, "주소"));
        for (int i = 0; i < places.size(); i++) id(places.get(i), 11 + i);
        places.getFirst().applyTags("조용함", "INDOOR");
        places.getFirst().applyReviewSummary("긴 리뷰 요약도 그대로 보낸다");
        List<GooglePlacesNearbySearchResponse.Place> raw = places.stream().map(p -> new GooglePlacesNearbySearchResponse.Place(p.getGooglePlaceId(),
                new GooglePlacesNearbySearchResponse.Place.DisplayName(p.getName()), List.of("cafe"),
                p.getRating(), p.getUserRatingCount(), null, null, "주소")).toList();
        when(trips.findById(1L)).thenReturn(Optional.of(target));
        when(trips.findByItineraryOrderByVisitOrder(itinerary)).thenReturn(List.of(target));
        when(trips.findByItinerary_Trip(itinerary.getTrip())).thenReturn(List.of(target));
        when(trips.findByTripOrderByDayAndVisitOrder(itinerary.getTrip())).thenReturn(List.of(target));
        when(google.searchNearby(anyDouble(), anyDouble(), anyDouble(), nullable(String.class)))
                .thenReturn(new GooglePlacesNearbySearchResponse(raw));
        when(catalog.upsertAll(raw)).thenReturn(places);
        when(repository.findIdsWithEmbedding(anyList())).thenAnswer(inv -> {
            assertSame(requestThread, Thread.currentThread());
            return List.of(12L);
        });
        doAnswer(inv -> {
            assertSame(requestThread, Thread.currentThread());
            vectors.putAll(inv.getArgument(0));
            return null;
        }).when(jdbc).updateEmbeddings(anyMap());
        when(repository.findEmbeddingText(anyLong())).thenAnswer(inv -> Optional.ofNullable(vectors.get(inv.getArgument(0))));
        when(crowd.fetchCongestion("경복궁")).thenAnswer(inv -> {
            assertSame(requestThread, Thread.currentThread());
            if (awaitCongestion) {
                assertTrue(embeddingStarted.await(5, TimeUnit.SECONDS));
            }
            congestionCalled.countDown();
            delay(congestionDelay);
            return Optional.of(new SeoulCongestionResponse(new SeoulCongestionResponse.CityData(List.of(
                    new SeoulCongestionResponse.LivePopulation("보통", "")))));
        });
        when(signals.findCandidateSimilarities(eq(1L), anyList())).thenAnswer(inv -> {
            assertSame(requestThread, Thread.currentThread());
            assertEquals(4, vectors.size(), "필터 탈락 후보도 신호에 쓰이므로 생성 후 조회해야 한다");
            delay(scoreDelay);
            List<Long> ids = inv.getArgument(1);
            return ids.stream().map(cid -> (UserPreferenceSignalRepository.CandidateSimilarity) new UserPreferenceSignalRepository.CandidateSimilarity() {
                public Long getCandidateId() { return cid; }
                public double getSimilarity() { return cid == 11L ? .9 : .3; }
            }).toList();
        });
        when(signals.findTopSimilarSignals(anyLong(), anyString())).thenAnswer(inv -> {
            assertEquals(4, vectors.size());
            double value = "[0.9]".equals(inv.getArgument(1)) ? .9 : .3;
            return List.of(new UserPreferenceSignalRepository.SimilarSignal() {
                public String getName() { return "선호신호"; }
                public String getCategory() { return "cafe"; }
                public String getMood() { return null; }
                public double getSimilarity() { return value; }
            });
        });
        when(text.generate(anyString())).thenReturn(Optional.of("취향에 맞는 카페예요"));
        PersonalizationService personal = spy(new PersonalizationService(repository, signals, text, fakeGemini, apiLog));
        Map<Long, Double> scores = new LinkedHashMap<>();
        doAnswer(inv -> {
            Map<Long, Double> result = (Map<Long, Double>) inv.callRealMethod(); scores.putAll(result); return result;
        }).when(personal).personalizationScores(anyLong(), anyList());
        doAnswer(inv -> {
            PersonalizationService.PersonalizationResult result = (PersonalizationService.PersonalizationResult) inv.callRealMethod();
            scores.put(((Place) inv.getArgument(1)).getId(), result.score()); return result;
        }).when(personal).retrieveAndScore(any(), any());
        Class<?> embeddingClass = baseline ? Class.forName("com.trova.backend.recommendation.BeforePlaceEmbeddingService") : PlaceEmbeddingService.class;
        Object embeddingService = embeddingClass.getConstructors()[0].newInstance(fakeGemini, repository, jdbc, apiLog);
        Class<?> finderClass = baseline ? Class.forName("com.trova.backend.recommendation.BeforeAlternativeFinderService") : AlternativeFinderService.class;
        Object finder = baseline
                ? finderClass.getConstructors()[0].newInstance(google, catalog, trips, mock(PlaceTaggingRunner.class),
                        crowd, apiLog, embeddingService, personal)
                : new AlternativeFinderService(google, catalog, trips, mock(PlaceTaggingRunner.class),
                        crowd, apiLog, (PlaceEmbeddingService) embeddingService, personal, repository, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(finder, "geminiExplanationEnabled", explanations);
        long start = System.nanoTime();
        Optional<List<AlternativeCandidate>> actual = (Optional<List<AlternativeCandidate>>) finderClass.getMethod("findAlternatives", User.class, Long.class, AlternativeFilter.class)
                .invoke(finder, user, 1L, new AlternativeFilter(null, null, .5, null, null));
        if (outsideDistance) {
            verify(crowd, never()).fetchCongestion("경복궁");
        } else if (awaitCongestion) {
            verify(crowd, times(1)).fetchCongestion("경복궁");
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        return new Run(actual.orElseThrow(), scores, vectors, calls.get(), List.copyOf(submitted), millis);
    }

    @Test
    void 혼잡도_조회는_대안_임베딩_HTTP_대기중에_실행된다() throws Exception {
        Run actual = run(false, true, false, 0, 0, 0, true, false);
        assertEquals(1, actual.calls());
        assertTrue(actual.candidates().get(1).isCongestionAvailable());
    }

    @Test
    void 거리_필터_밖의_경복궁은_혼잡도를_조회하지_않는다() throws Exception {
        Run actual = run(false, true, false, 0, 0, 0, false, true);
        assertThat(actual.candidates()).extracting(AlternativeCandidate::googlePlaceId).containsExactly("a");
    }

    @Test
    void 기존_결과_점수_이유_벡터_대상과_한번의_묶음을_유지한다() throws Exception {
        for (boolean explanations : List.of(false, true)) {
            Run actual = run(false, true, explanations, 0, 0, 0);
            assertThat(actual.candidates()).extracting(AlternativeCandidate::googlePlaceId).containsExactly("a", "b");
            assertThat(actual.candidates()).extracting(AlternativeCandidate::recommendationReason)
                    .containsExactly(explanations ? "취향에 맞는 카페예요" : "원래 장소에서 직선 111m · 같은 종류의 카페예요",
                            explanations ? "취향에 맞는 카페예요" : "원래 장소에서 직선 111m · 같은 종류의 카페예요");
            assertEquals(.9, actual.scores().get(11L));
            assertEquals(.3, actual.scores().get(12L));
            assertEquals(1, actual.calls());
            assertThat(actual.texts()).containsExactly(List.of("신규 cafe 조용함 긴 리뷰 요약도 그대로 보낸다", "거리탈락_선호신호 cafe", "좌표없음 cafe"));
            assertEquals(4, actual.vectors().size());
            assertTrue(actual.candidates().get(1).isCongestionAvailable());
        }
    }

    @Test
    void 원본_main과_같은_입력의_결과를_비교하고_가짜_지연을_측정한다() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("t46.baseline"));
        List<String> report = new ArrayList<>();
        for (boolean congestion : List.of(false, true)) {
            for (int iteration = 0; iteration < 3; iteration++) {
                Run before = run(true, congestion, false, 1200, 300, 300);
                Run after = run(false, congestion, false, 1200, 300, 300);
                assertEquals(before.candidates(), after.candidates());
                assertEquals(before.scores(), after.scores());
                assertEquals(before.vectors(), after.vectors());
                assertEquals(before.texts(), after.texts());
                assertEquals(1, before.calls()); assertEquals(1, after.calls());
                report.add("혼잡도=" + congestion + ", before=" + before.millis() + ", after=" + after.millis() + ", batches=1/1");
            }
        }
        for (boolean explanations : List.of(false, true)) {
            Run before = run(true, true, explanations, 0, 0, 0);
            Run after = run(false, true, explanations, 0, 0, 0);
            assertEquals(before.candidates(), after.candidates());
            assertEquals(before.scores(), after.scores());
        }
        java.nio.file.Files.write(java.nio.file.Path.of(System.getProperty("t46.report")), report);
    }

    @Test
    void Gemini_HTTP와_독립작업이_겹치고_DB는_요청스레드에_남는다() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch work = new CountDownLatch(1);
        GeminiEmbeddingClient client = mock(GeminiEmbeddingClient.class);
        PlaceRepository repo = mock(PlaceRepository.class);
        PlaceEmbeddingJdbcRepository jdbc = mock(PlaceEmbeddingJdbcRepository.class);
        ApiCallLogService apiLog = mock(ApiCallLogService.class);
        Thread main = Thread.currentThread();
        when(client.embedBatch(anyList())).thenAnswer(inv -> {
            assertNotSame(main, Thread.currentThread());
            assertTrue(Thread.currentThread().isVirtual());
            started.countDown();
            assertTrue(work.await(3, TimeUnit.SECONDS), "독립 작업을 생성 뒤로 되돌리면 실패");
            return Optional.of(List.of(new float[]{1}));
        });
        when(repo.findIdsWithEmbedding(anyList())).thenAnswer(inv -> { assertSame(main, Thread.currentThread()); return List.of(); });
        doAnswer(inv -> { assertSame(main, Thread.currentThread()); assertEquals(0, work.getCount()); return null; })
                .when(jdbc).updateEmbeddings(anyMap());
        Place place = new Place("a", "가", "cafe", null, null, null, 37., 127., null); id(place, 1);
        PlaceEmbeddingService service = new PlaceEmbeddingService(client, repo, jdbc, apiLog);
        PlaceEmbeddingService.EmbeddingStats stats = service.ensureEmbeddingsWithWork(List.of(place), () -> {
            try { assertTrue(started.await(3, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
            work.countDown();
        });
        assertEquals(new PlaceEmbeddingService.EmbeddingStats(1, 1), stats);
        verify(client, times(1)).embedBatch(List.of("가 cafe"));
    }
}
