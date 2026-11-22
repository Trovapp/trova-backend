package com.trova.backend.controller;

import com.trova.backend.entity.*;
import com.trova.backend.recommendation.GooglePlacesApiClient;
import com.trova.backend.recommendation.GooglePlacesNearbySearchResponse;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.recommendation.PlaceReviewService;
import com.trova.backend.pipeline.ReviewSummary;
import com.trova.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 영상에서 찾은 장소를 여행에 담기(#126)·찜하기(#125). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VideoPlaceActionsTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private ItineraryRepository itineraryRepository;
    @Autowired
    private TripPlaceRepository tripPlaceRepository;
    @Autowired
    private PlaceRepository placeRepository;
    @Autowired
    private ProcessingJobRepository processingJobRepository;
    @Autowired
    private SavedPlaceRepository savedPlaceRepository;
    @Autowired
    private BookmarkRepository bookmarkRepository;

    @MockitoBean
    private GooglePlacesApiClient googlePlacesApiClient;
    @MockitoBean
    private PlaceEmbeddingService placeEmbeddingService;
    @MockitoBean
    private PlaceReviewService placeReviewService;

    @BeforeEach
    void noSearchResults() {
        when(googlePlacesApiClient.searchText(anyString())).thenReturn(new GooglePlacesNearbySearchResponse(List.of()));
    }

    private RequestPostProcessor loginAs(String sub) {
        ClientRegistration google = ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .build();
        return oauth2Login().clientRegistration(google).attributes(a -> {
            a.put("sub", sub);
            a.put("name", "영상유저");
            a.put("picture", "https://example.com/p.jpg");
        });
    }

    private SavedPlace videoPlace(User user, String name) {
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(user, "https://youtu.be/" + name, SourcePlatform.YOUTUBE));
        return savedPlaceRepository.save(new SavedPlace(job, user, name, "제주", "cafe", 33.45, 126.5, 1, 1));
    }

    private Trip trip(User user) {
        Trip trip = tripRepository.save(new Trip(user, "제주 여행", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)));
        itineraryRepository.save(new Itinerary(trip, 1, LocalDate.of(2026, 11, 1)));
        itineraryRepository.save(new Itinerary(trip, 2, LocalDate.of(2026, 11, 2)));
        return trip;
    }

    @Test
    void 영상_장소를_여행의_일차_끝에_담는다() throws Exception {
        User me = userRepository.save(new User("google", "vp-trip-1", "영상유저", null));
        SavedPlace cafe = videoPlace(me, "바다카페");
        cafe.applyVideoNotes(List.of("창가 자리에서 바다가 보인다"));
        savedPlaceRepository.save(cafe);
        Trip trip = trip(me);

        mockMvc.perform(post("/api/trips/" + trip.getId() + "/days/2/video-places").with(loginAs("vp-trip-1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"savedPlaceId\":" + cafe.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeName").value("바다카페"));

        Itinerary day2 = itineraryRepository.findByTripAndDay(trip, 2).orElseThrow();
        List<TripPlace> places = tripPlaceRepository.findByItineraryOrderByVisitOrder(day2);
        assertThat(places).extracting(TripPlace::getPlaceName).containsExactly("바다카페");
        assertThat(places.get(0).getSource()).isEqualTo(PlaceSource.VIDEO);
        assertThat(places.get(0).getMemo()).isEqualTo("· 창가 자리에서 바다가 보인다");
        verify(googlePlacesApiClient, never()).searchText(anyString());
    }

    @Test
    void 편집자는_본인_영상의_장소를_공동_일정에_담고_상세를_볼_수_있다() throws Exception {
        User owner = userRepository.save(new User("google", "video-owner", "owner", null));
        User editor = userRepository.save(new User("google", "video-editor", "editor", null));
        Trip shared = trip(owner);
        shared.getMembers().add(new TripMember(editor, TripMember.Role.EDITOR));
        tripRepository.saveAndFlush(shared);
        SavedPlace cafe = videoPlace(editor, "바다카페");
        placeRepository.save(new Place("collab-cafe", "바다카페", "cafe", 4.4, 30, null, 33.45, 126.5, "제주"));
        mockMvc.perform(post("/api/trips/" + shared.getId() + "/days/1/video-places").with(loginAs("video-editor"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"savedPlaceId\":" + cafe.getId() + "}"))
                .andExpect(status().isOk());
        TripPlace item = tripPlaceRepository.findByItinerary_Trip(shared).getFirst();
        item.applyGooglePlaceId("collab-cafe");
        when(placeReviewService.getOrGenerateSummary(anyLong())).thenReturn(Optional.of(new PlaceReviewService.PlaceReviewInfo(
                new ReviewSummary("카페", List.of(), List.of(), null, null, List.of(), List.of()), List.of())));
        mockMvc.perform(get("/api/trip-places/" + item.getId() + "/details").with(loginAs("video-editor")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("바다카페"));
    }

    @Test
    void 이미_담긴_영상_장소는_같은_날이면_409로_막고_다른_날은_확인하면_담는다() throws Exception {
        User me = userRepository.save(new User("google", "vp-trip-dup", "영상유저", null));
        SavedPlace cafe = videoPlace(me, "바다카페");
        Trip trip = trip(me);
        String body = "{\"savedPlaceId\":" + cafe.getId() + "}";
        mockMvc.perform(post("/api/trips/" + trip.getId() + "/days/1/video-places").with(loginAs("vp-trip-dup"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/trips/" + trip.getId() + "/days/1/video-places").with(loginAs("vp-trip-dup"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SAME_DAY"))
                .andExpect(jsonPath("$.day").value(1))
                .andExpect(jsonPath("$.placeName").value("바다카페"));
        mockMvc.perform(post("/api/trips/" + trip.getId() + "/days/2/video-places").with(loginAs("vp-trip-dup"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_OTHER_DAY"))
                .andExpect(jsonPath("$.day").value(1));
        mockMvc.perform(post("/api/trips/" + trip.getId() + "/days/2/video-places").with(loginAs("vp-trip-dup"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"savedPlaceId\":" + cafe.getId() + ",\"allowOtherDay\":true}"))
                .andExpect(status().isOk());

        assertThat(tripPlaceRepository.findByItinerary_Trip(trip)).hasSize(2);
    }

    @Test
    void 남의_영상_장소나_남의_여행에는_담지_못한다() throws Exception {
        User me = userRepository.save(new User("google", "vp-trip-2", "영상유저", null));
        User other = userRepository.save(new User("google", "vp-trip-other", "남", null));
        SavedPlace othersPlace = videoPlace(other, "남의카페");
        Trip myTrip = trip(me);
        SavedPlace mine = videoPlace(me, "내카페");
        Trip othersTrip = trip(other);

        mockMvc.perform(post("/api/trips/" + myTrip.getId() + "/days/1/video-places").with(loginAs("vp-trip-2"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"savedPlaceId\":" + othersPlace.getId() + "}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/trips/" + othersTrip.getId() + "/days/1/video-places").with(loginAs("vp-trip-2"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"savedPlaceId\":" + mine.getId() + "}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 일정_초안의_영상_장소도_리뷰_요약을_본다() throws Exception {
        // #235: 일정 초안 장소는 아직 여행 장소가 아니라 영상 장소 — 찜하기와 같은 규칙으로 구글 장소에 연결해 리뷰를 준다.
        User me = userRepository.save(new User("google", "vp-review-1", "영상유저", null));
        placeRepository.save(new Place("gp-video-review", "바다카페", "cafe", 4.4, 30, null, 33.45, 126.5, "제주"));
        SavedPlace cafe = videoPlace(me, "바다카페");
        cafe.applyOpeningHours("gp-video-review", null);
        savedPlaceRepository.save(cafe);
        when(placeReviewService.getOrGenerateSummary(anyLong())).thenReturn(Optional.of(new PlaceReviewService.PlaceReviewInfo(
                new ReviewSummary("통창 **바다 뷰**", List.of("뷰가 좋다"), List.of("주말엔 붐빈다"), null, null, List.of(), List.of()),
                List.of("바다가 보여요"))));

        mockMvc.perform(get("/api/saved-places/" + cafe.getId() + "/details").with(loginAs("vp-review-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("바다카페"))
                .andExpect(jsonPath("$.pros[0]").value("뷰가 좋다"));
        verify(googlePlacesApiClient, never()).searchText(anyString());
    }

    @Test
    void 남의_영상_장소_리뷰는_볼_수_없다() throws Exception {
        userRepository.save(new User("google", "vp-review-2", "영상유저", null));
        User other = userRepository.save(new User("google", "vp-review-other", "남", null));
        SavedPlace others = videoPlace(other, "남의카페");

        mockMvc.perform(get("/api/saved-places/" + others.getId() + "/details").with(loginAs("vp-review-2")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 구글_장소가_연결된_영상_장소는_검색_없이_찜한다() throws Exception {
        User me = userRepository.save(new User("google", "vp-bm-1", "영상유저", null));
        Place catalog = placeRepository.save(new Place("gp-video-1", "바다카페", "cafe", 4.4, 30, null, 33.45, 126.5, "제주"));
        SavedPlace cafe = videoPlace(me, "바다카페");
        cafe.applyOpeningHours("gp-video-1", null);
        savedPlaceRepository.save(cafe);

        mockMvc.perform(post("/api/bookmarks/saved-place").with(loginAs("vp-bm-1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"savedPlaceId\":" + cafe.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addressSource").value("PLACE_CATALOG"));

        assertThat(bookmarkRepository.findByUserAndPlace(me, catalog)).isPresent();
        verify(googlePlacesApiClient, never()).searchText(anyString());
    }

    @Test
    void 경포호의_기존_ID가_먼_핀을_가리키면_저장을_거절한다() throws Exception {
        User user = userRepository.save(new User("google", "lake", "나", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(user, "https://youtu.be/lake", SourcePlatform.YOUTUBE));
        SavedPlace lake = new SavedPlace(job, user, "경포호", "강릉", "attraction", 37.80, 128.91,
                null, null, null, "운정동 984", null, null, null);
        lake.applyOpeningHours("lake-pin", null);
        savedPlaceRepository.save(lake);
        placeRepository.save(new Place("lake-pin", "경포호(鏡浦湖)", "attraction", null, null, null,
                37.795074, 128.896636, "저동 94"));
        when(googlePlacesApiClient.searchText("강릉 경포호")).thenReturn(new GooglePlacesNearbySearchResponse(List.of(
                new GooglePlacesNearbySearchResponse.Place("lake-pin",
                        new GooglePlacesNearbySearchResponse.Place.DisplayName("경포호(鏡浦湖)"), List.of("park"),
                        null, null, null, new GooglePlacesNearbySearchResponse.Place.Location(37.795074, 128.896636),
                        "저동 94"))));
        mockMvc.perform(get("/api/places/" + lake.getId()).with(loginAs("lake")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.address").value("운정동 984"))
                .andExpect(jsonPath("$.addressSource").value("ANALYSIS_RESULT"));
        mockMvc.perform(post("/api/bookmarks/saved-place").with(loginAs("lake"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"savedPlaceId\":" + lake.getId() + "}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 경포호_옆의_경포호호텔은_건너뛰고_한자_병기된_호수를_저장한다() throws Exception {
        User user = userRepository.save(new User("google", "lake-near", "나", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(user, "https://youtu.be/near", SourcePlatform.YOUTUBE));
        SavedPlace lake = savedPlaceRepository.save(new SavedPlace(job, user, "경포호", "강릉", "attraction", 37.79556, 128.90234,
                null, null, null, "운정동 984", null, null, null));
        Place hotel = placeRepository.save(new Place("lake-hotel", "경포호호텔", "hotel", null, null, null,
                37.79556, 128.90234, "호텔 주소"));
        Place correct = placeRepository.save(new Place("lake-near", "경포호(鏡浦湖)", "park", null, null, null,
                37.79670, 128.90205, "저동 94"));
        Place duplicate = placeRepository.save(new Place("lake-second", "경포호", "park", null, null, null,
                37.79685, 128.90228, "강릉시 경포호"));
        List<GooglePlacesNearbySearchResponse.Place> candidates = List.of(hotel, duplicate, correct).stream()
                .map(p -> new GooglePlacesNearbySearchResponse.Place(p.getGooglePlaceId(),
                        new GooglePlacesNearbySearchResponse.Place.DisplayName(p.getName()), List.of(p.getCategory()),
                        null, null, null, new GooglePlacesNearbySearchResponse.Place.Location(p.getLatitude(), p.getLongitude()),
                        p.getAddress())).toList();
        when(googlePlacesApiClient.searchText("강릉 경포호")).thenReturn(new GooglePlacesNearbySearchResponse(candidates));
        mockMvc.perform(post("/api/bookmarks/saved-place").with(loginAs("lake-near"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"savedPlaceId\":" + lake.getId() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.placeId").value(correct.getId()))
                .andExpect(jsonPath("$.address").value("저동 94"));
    }

    @Test
    void 지도에서_찾지_못한_영상_장소는_찜하지_않고_알린다() throws Exception {
        User me = userRepository.save(new User("google", "vp-bm-2", "영상유저", null));
        SavedPlace unknown = videoPlace(me, "어딘가카페");

        mockMvc.perform(post("/api/bookmarks/saved-place").with(loginAs("vp-bm-2"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"savedPlaceId\":" + unknown.getId() + "}"))
                .andExpect(status().isUnprocessableEntity());
        verify(googlePlacesApiClient).searchText("제주 어딘가카페");
    }
}
