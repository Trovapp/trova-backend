package com.trova.backend.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.congestion.SeoulCongestionApiClient;
import com.trova.backend.embedding.GeminiEmbeddingClient;
import com.trova.backend.embedding.GeminiTextClient;
import com.trova.backend.entity.*;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.repository.*;
import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 오프라인 재생 전용. 실제 서비스 순위/이유/개인화 평균을 재사용하며 모든 I/O 경계는 mock. */
class EmbeddingDeferReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static void id(Object x, long value) throws Exception {
        var field = x.getClass().getDeclaredField("id"); field.setAccessible(true); field.set(x, value);
    }
    private static UserPreferenceSignalRepository.CandidateSimilarity row(long id, double value) {
        return new UserPreferenceSignalRepository.CandidateSimilarity() {
            public Long getCandidateId() { return id; }
            public double getSimilarity() { return value; }
        };
    }
    private static String text(JsonNode n, String key) { return n.path(key).isNull() ? null : n.path(key).asText(); }

    private List<Map<String,Object>> replay(JsonNode request, String mode, String mask) throws Exception {
        var google = mock(GooglePlacesApiClient.class);
        var catalog = mock(PlaceCatalogService.class);
        var trips = mock(TripPlaceRepository.class);
        var embeddings = mock(PlaceEmbeddingService.class);
        var signals = mock(UserPreferenceSignalRepository.class);
        var geminiText = mock(GeminiTextClient.class);
        var geminiEmbedding = mock(GeminiEmbeddingClient.class);
        var log = mock(ApiCallLogService.class);
        var personalization = new PersonalizationService(mock(PlaceRepository.class), signals, geminiText, geminiEmbedding, log);
        var service = new AlternativeFinderService(google, catalog, trips, mock(PlaceTaggingRunner.class),
                mock(SeoulCongestionApiClient.class), log, embeddings, personalization);
        var user = new User("offline", "U", "U", null); id(user, 1);
        var itinerary = new Itinerary(new Trip(user, "offline", null, null), 1, null);
        // 실제 좌표 대신 공개된 직선거리만 유지. 필터 없는 단일 장소·저녁 조건과 동일.
        var target = new TripPlace(itinerary, "TARGET", null, text(request,"target_category"),
                37.0, 127.0, null, null, 1, PlaceSource.NORMAL, null); id(target, 999999);
        var candidates = new ArrayList<Place>();
        var raw = new ArrayList<GooglePlacesNearbySearchResponse.Place>();
        var primary = new ArrayList<GooglePlacesNearbySearchResponse.Place>();
        var rows = new ArrayList<UserPreferenceSignalRepository.CandidateSimilarity>();
        boolean cold = mask.equals("all-new");
        for (JsonNode c : request.path("candidates")) cold |= !c.path("embedded_before").asBoolean();
        for (JsonNode c : request.path("candidates")) {
            long cid = c.path("input_order").asLong()+1;
            String alias = c.path("candidate_alias").asText();
            double lat = 37.0 + Math.toDegrees(c.path("distance_target_km").asDouble()/6371.0);
            Double rating = c.path("rating").isNull() ? null : c.path("rating").asDouble();
            Integer reviews = c.path("user_rating_count").isNull() ? null : c.path("user_rating_count").asInt();
            var p = new Place(alias, alias, text(c,"category"), rating, reviews, null, lat, 127.0, null); id(p,cid);
            candidates.add(p);
            var r = new GooglePlacesNearbySearchResponse.Place(alias,
                    new GooglePlacesNearbySearchResponse.Place.DisplayName(alias),
                    p.getCategory()==null ? List.of() : List.of(p.getCategory()),rating,reviews,null,
                    new GooglePlacesNearbySearchResponse.Place.Location(lat,127.0), null);
            raw.add(r); if(c.path("primary").asBoolean()) primary.add(r);
            boolean available = mask.equals("mixed") && c.path("embedded_before").asBoolean();
            if(mode.equals("A") || (mode.equals("B1") && !cold) || (mode.equals("B2") || mode.equals("B3")) && available) {
                for(JsonNode v:c.path("similarities")) rows.add(row(cid, v.asDouble()));
            } else if(mode.equals("B3")) {
                rows.add(row(cid,c.path("category_proxy").asDouble()));
            }
        }
        when(trips.findById(999999L)).thenReturn(Optional.of(target));
        when(trips.findByItinerary_Trip(itinerary.getTrip())).thenReturn(List.of(target));
        when(trips.findByItineraryOrderByVisitOrder(itinerary)).thenReturn(List.of(target));
        when(google.searchNearby(anyDouble(),anyDouble(),anyDouble(),nullable(String.class))).thenAnswer(a ->
                new GooglePlacesNearbySearchResponse(a.getArgument(3)==null ? raw : primary));
        when(catalog.upsertAll(anyList())).thenAnswer(a -> {
            List<GooglePlacesNearbySearchResponse.Place> selected=a.getArgument(0);
            return selected.stream().map(r->candidates.stream().filter(p->p.getGooglePlaceId().equals(r.id())).findFirst().orElseThrow()).toList();
        });
        when(signals.findCandidateSimilarities(eq(1L),anyList())).thenReturn(rows);
        List<AlternativeCandidate> ranked = service.findAlternatives(user,999999L,
                new AlternativeFilter(null,null,null,null,null)).orElseThrow();
        assertEquals(candidates.size(),ranked.size(),request.path("request_alias").asText()+" 재생 후보 보존");
        verifyNoInteractions(geminiText,geminiEmbedding);
        var result = new ArrayList<Map<String,Object>>();
        for(var c:ranked) {
            var item = new LinkedHashMap<String,Object>(); item.put("candidate_alias",c.googlePlaceId());
            item.put("reason",c.recommendationReason()); result.add(item);
        }
        return result;
    }

    @Test void replayActualServicesWithoutExternalCalls() throws Exception {
        JsonNode input=JSON.readTree(Files.readString(Path.of(System.getProperty("t45.input"))));
        var output=new ArrayList<Map<String,Object>>();
        for(JsonNode request:input) {
            var item=new LinkedHashMap<String,Object>(); item.put("request_alias",request.path("request_alias").asText());
            item.put("A",replay(request,"A","mixed"));
            for(String mask:List.of("mixed","all-new")) for(String mode:List.of("B1","B2","B3"))
                item.put(mode+"/"+mask,replay(request,mode,mask));
            output.add(item);
        }
        Files.writeString(Path.of(System.getProperty("t45.output")),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output)+"\n");
    }

    @Test void syntheticFixtureChecksPersonalizationStableTiesPrimaryAndReasons() throws Exception {
        // 개인화가 순위를 뒤집는 사례, 동점 안정성, primary 우선, 이유의 취향 근거 소실을 검증.
        JsonNode fixture=JSON.readTree("""
          {"request_alias":"SYNTHETIC","target_category":"art_museum","candidates":[
           {"input_order":0,"candidate_alias":"POPULAR","primary":true,"category":"cafe","rating":4,"user_rating_count":100,"distance_target_km":1,"embedded_before":false,"similarities":[0.1],"category_proxy":0},
           {"input_order":1,"candidate_alias":"PREFERRED","primary":true,"category":"park","rating":4,"user_rating_count":80,"distance_target_km":1,"embedded_before":false,"similarities":[1.0],"category_proxy":0.5},
           {"input_order":2,"candidate_alias":"TIE","primary":true,"category":"cafe","rating":4,"user_rating_count":100,"distance_target_km":1,"embedded_before":true,"similarities":[0.1],"category_proxy":0},
           {"input_order":3,"candidate_alias":"SUPPLEMENT","primary":false,"category":"park","rating":5,"user_rating_count":10000,"distance_target_km":1,"embedded_before":false,"similarities":[1.0],"category_proxy":1}
          ]}
          """);
        // art_museum은 defaultType 무매핑이라 primary를 강제로 분리할 수 없어 알려진 museum 사용.
        ((com.fasterxml.jackson.databind.node.ObjectNode)fixture).put("target_category","museum");
        // 서비스는 primary 3개면 보충하지 않으므로 primary 우선 검증용은 primary 2개로 구성.
        ((com.fasterxml.jackson.databind.node.ObjectNode)fixture.path("candidates").get(2)).put("primary",false);
        var a=replay(fixture,"A","mixed");var b=replay(fixture,"B2","all-new");
        assertEquals(List.of("PREFERRED","POPULAR","SUPPLEMENT","TIE"),a.stream().map(x->x.get("candidate_alias")).toList());
        assertEquals(List.of("POPULAR","PREFERRED","SUPPLEMENT","TIE"),b.stream().map(x->x.get("candidate_alias")).toList());
        assertTrue(a.getFirst().get("reason").toString().contains("취향"));
        assertFalse(b.getFirst().get("reason").toString().contains("취향"));
        var noSignals=fixture.deepCopy();
        for(JsonNode c:noSignals.path("candidates")) ((com.fasterxml.jackson.databind.node.ObjectNode)c).putArray("similarities");
        assertEquals(replay(noSignals,"A","mixed"),replay(noSignals,"B2","all-new"));
        var warm=fixture.deepCopy();
        for(JsonNode c:warm.path("candidates")) ((com.fasterxml.jackson.databind.node.ObjectNode)c).put("embedded_before",true);
        assertEquals(replay(warm,"A","mixed"),replay(warm,"B1","mixed"));
        assertEquals(replay(warm,"A","mixed"),replay(warm,"B2","mixed"));
    }
}
