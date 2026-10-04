package com.trova.backend.controller;

import com.trova.backend.entity.*;
import com.trova.backend.recommendation.GooglePlacesApiClient;
import com.trova.backend.recommendation.GooglePlacesNearbySearchResponse;
import com.trova.backend.recommendation.PlaceEmbeddingService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
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
        Trip trip = trip(me);

        mockMvc.perform(post("/api/trips/" + trip.getId() + "/days/2/video-places").with(loginAs("vp-trip-1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"savedPlaceId\":" + cafe.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeName").value("바다카페"));

        Itinerary day2 = itineraryRepository.findByTripAndDay(trip, 2).orElseThrow();
        List<TripPlace> places = tripPlaceRepository.findByItineraryOrderByVisitOrder(day2);
        assertThat(places).extracting(TripPlace::getPlaceName).containsExactly("바다카페");
        assertThat(places.get(0).getSource()).isEqualTo(PlaceSource.VIDEO);
        verify(googlePlacesApiClient, never()).searchText(anyString());
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
                .andExpect(status().isOk());

        assertThat(bookmarkRepository.findByUserAndPlace(me, catalog)).isPresent();
        verify(googlePlacesApiClient, never()).searchText(anyString());
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
