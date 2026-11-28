package com.trova.backend.controller;

import com.trova.backend.events.TripChangedEvent;
import com.trova.backend.entity.Bookmark;
import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.security.OAuth2UserInfo;
import com.trova.backend.service.TripMembershipService;
import com.trova.backend.service.TripService;
import com.trova.backend.service.TripShareLinkService;
import com.trova.backend.service.UserUpsertService;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.springframework.test.util.ReflectionTestUtils;
import com.trova.backend.entity.BookmarkFolder;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripMember;
import com.trova.backend.entity.User;
import com.trova.backend.repository.BookmarkFolderRepository;
import com.trova.backend.repository.BookmarkRepository;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.recommendation.GooglePlacesNearbySearchResponse;
import com.trova.backend.recommendation.PlaceCatalogService;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.entity.ProcessingStage;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.trova.backend.service.ProcessingJobLifecycleService;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class UserListCacheTest {
    @MockitoBean private PlaceEmbeddingService embeddings; // H2에는 pgvector 컬럼이 없다.
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private BookmarkRepository bookmarks;
    @Autowired private BookmarkFolderRepository folders;
    @Autowired private SavedPlaceRepository saved;
    @Autowired private ProcessingJobRepository jobs;
    @Autowired private PlaceRepository places;
    @Autowired private PlaceCatalogService catalog;
    @Autowired private ApplicationEventMulticaster events;
    @Autowired private TransactionTemplate tx;
    @Autowired private MeterRegistry metrics;
    @Autowired private ProcessingJobLifecycleService lifecycle;
    @Autowired private JsonMapper json;

    @Autowired private TripMembershipService memberships;
    @Autowired private TripShareLinkService shareLinks;
    @Autowired private UserUpsertService profiles;
    @Autowired private TripService tripService;
    @Autowired private EntityManager em;
    @Autowired private UserListReads reads;

    private String read(String token, String list) throws Exception {
        return mvc.perform(get("/api/" + list).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void 세_목록은_두번째_조회에_SQL이_없고_다른_사용자와_공유하지_않는다() throws Exception {
        User owner = users.save(new User("google", "list-cache-owner", "Owner", null));
        String token = jwt.issue(owner);
        String other = jwt.issue(users.save(new User("google", "list-cache-other", "Other", null)));
        seedTrip(owner);
        seedBookmark(owner);
        seedPlace(owner);

        for (String list : List.of("places", "bookmarks", "trips")) {
            String initial = read(token, list);
            assertThat(initial).isNotEqualTo("[]");
            assertHit(token, list, initial);
            assertThat(read(other, list)).isEqualTo("[]");
        }
    }

    @Test
    void 커밋된_추가_수정_삭제가_세_목록에_즉시_반영된다() throws Exception {
        User owner = users.save(new User("google", "list-cache-writes", "Owner", null));
        String token = jwt.issue(owner);
        assertThat(read(token, "trips")).isEqualTo("[]");
        Trip trip = seedTrip(owner);
        assertThat(read(token, "trips")).contains("First trip");
        trip.applyTitle("Updated trip");
        trips.save(trip);
        assertThat(read(token, "trips")).contains("Updated trip");
        trips.deleteById(trip.getId());
        assertThat(read(token, "trips")).isEqualTo("[]");

        assertThat(read(token, "places")).isEqualTo("[]");
        SavedPlace place = seedPlace(owner);
        assertThat(read(token, "places")).contains("First stop");
        place.suggestNearbyBranch("Updated branch");
        saved.save(place);
        assertThat(read(token, "places")).contains("Updated branch");
        saved.deleteById(place.getId());
        assertThat(read(token, "places")).isEqualTo("[]");

        BookmarkFolder folder = folders.save(new BookmarkFolder(owner, "Folder", "#000000"));
        assertThat(read(token, "bookmarks")).isEqualTo("[]");
        Bookmark bookmark = seedBookmark(owner);
        assertThat(read(token, "bookmarks")).contains("First bookmark");
        bookmark.applyFolder(folder);
        bookmarks.save(bookmark);
        assertThat(read(token, "bookmarks")).contains("\"folderId\":" + folder.getId());
        bookmarks.deleteById(bookmark.getId());
        assertThat(read(token, "bookmarks")).isEqualTo("[]");
    }

    @Test
    void 공유_여행에서_제외하면_편집자의_다음_목록에서_사라진다() throws Exception {
        User owner = users.save(new User("google", "cache-member-owner", "Owner", null));
        User editor = users.save(new User("google", "cache-member-editor", "Editor", null));
        String token = jwt.issue(editor);
        Trip trip = seedTrip(owner);
        trip.getMembers().add(new TripMember(editor, TripMember.Role.EDITOR));
        Trip shared = trips.save(trip);
        assertThat(read(token, "trips")).contains("First trip", "EDITOR");
        shared.getMembers().removeIf(member -> member.getRole() == TripMember.Role.EDITOR);
        trips.save(shared);
        assertThat(read(token, "trips")).isEqualTo("[]");
    }

    private void assertHit(String token, String list, String expected) throws Exception {
        DistributionSummary requests = metrics.get("trova.db.queries.per.request")
                .tags("method", "GET", "uri", "/api/" + list).summary();
        double sql = requests.totalAmount();
        long count = requests.count();
        assertThat(read(token, list)).isEqualTo(expected);
        assertThat(requests.count()).isEqualTo(count + 1);
        assertThat(requests.totalAmount()).as(list).isEqualTo(sql);
    }

    @Test
    void 다른_사용자의_북마크와_분석_진행_쓰기는_내_세_목록을_다시_조회하지_않는다() throws Exception {
        User viewer = users.save(new User("google", "cache-hit-viewer", "Viewer", null));
        User writer = users.save(new User("google", "cache-hit-writer", "Writer", null));
        ProcessingJob job = jobs.save(new ProcessingJob(writer, "https://example.test/progress", SourcePlatform.YOUTUBE));
        String token = jwt.issue(viewer);
        for (String list : List.of("bookmarks", "places", "trips")) read(token, list);
        seedBookmark(writer);
        lifecycle.updateStage(job.getId(), ProcessingStage.GEOCODING);
        GooglePlacesNearbySearchResponse.Place candidate = new GooglePlacesNearbySearchResponse.Place(
                "cache-new-catalog", new GooglePlacesNearbySearchResponse.Place.DisplayName("New"),
                List.of("cafe"), 4.0, 1, null, new GooglePlacesNearbySearchResponse.Place.Location(1, 1), "Region");
        catalog.upsertAll(List.of(candidate));
        catalog.upsertAll(List.of(candidate));
        for (String list : List.of("bookmarks", "places", "trips")) assertHit(token, list, "[]");
    }

    @Test
    void HTTP_쓰기_직후_여행과_장소와_북마크_목록을_다시_읽는다() throws Exception {
        User owner = users.save(new User("google", "cache-http-owner", "Owner", null));
        String token = jwt.issue(owner);
        SavedPlace stop = seedPlace(owner);
        Place catalog = places.save(new Place("cache-http", "Cafe", "cafe", 4.0, 1, null, 1.0, 1.0, "Region"));

        assertThat(read(token, "trips")).isEqualTo("[]");
        String created = write(token, post("/api/trips"),
                "{\"title\":\"API trip\",\"startDate\":\"2026-11-01\",\"endDate\":\"2026-11-02\"}", 200);
        long tripId = json.readTree(created).get("id").asLong();
        assertThat(read(token, "trips")).contains("API trip", "\"placeCount\":0");
        write(token, post("/api/trips/" + tripId + "/days/1/places"), "{\"googlePlaceId\":\"cache-http\"}", 200);
        assertThat(read(token, "trips")).contains("\"placeCount\":1");

        assertThat(read(token, "bookmarks")).isEqualTo("[]");
        String added = write(token, post("/api/bookmarks"), "{\"placeId\":" + catalog.getId() + "}", 200);
        long bookmarkId = json.readTree(added).get("id").asLong();
        assertThat(read(token, "bookmarks")).contains("Cafe");
        write(token, delete("/api/bookmarks/" + bookmarkId), "", 204);
        assertThat(read(token, "bookmarks")).isEqualTo("[]");

        assertThat(read(token, "places")).contains("First stop");
        write(token, patch("/api/places/" + stop.getId() + "/day"), "{\"dayNumber\":2}", 200);
        assertThat(read(token, "places")).contains("\"dayNumber\":2");
        write(token, delete("/api/places/" + stop.getId()), "", 204);
        assertThat(read(token, "places")).isEqualTo("[]");
    }

    private String write(String token, MockHttpServletRequestBuilder request, String body, int expected) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void 여행_변경_알림을_받은_순간_다른_요청은_새_목록을_읽는다() throws Exception {
        User owner = users.save(new User("google", "cache-event", "Owner", null));
        String token = jwt.issue(owner);
        Trip trip = seedTrip(owner);
        assertThat(read(token, "trips")).contains("First trip");
        AtomicReference<String> observed = new AtomicReference<>();
        try (ExecutorService client = Executors.newSingleThreadExecutor()) {
            ApplicationListener<ApplicationEvent> observer = event -> {
                if (event instanceof PayloadApplicationEvent<?> payload
                        && payload.getPayload() instanceof TripChangedEvent change && change.tripId() == trip.getId()) {
                    try {
                        observed.set(client.submit(() -> read(token, "trips")).get(5, TimeUnit.SECONDS));
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }
            };
            events.addApplicationListener(observer);
            try {
                write(token, patch("/api/trips/" + trip.getId() + "/title"), "{\"title\":\"Fresh trip\"}", 200);
                assertThat(observed.get()).contains("Fresh trip");
            } finally {
                events.removeApplicationListener(observer);
            }
        }
    }

    @Test
    void 카탈로그_벌크_수정은_커밋한_북마크에만_반영된다() throws Exception {
        User owner = users.save(new User("google", "cache-catalog", "Owner", null));
        String token = jwt.issue(owner);
        Place locationMissing = places.save(new Place("cache-no-coords", "Cafe", "cafe", 4.0, 1, null,
                null, null, "Region"));
        bookmarks.save(new Bookmark(owner, locationMissing));
        Long placeId = locationMissing.getId();
        String other = jwt.issue(users.save(new User("google", "cache-other-catalog", "Other", null)));
        assertThat(read(other, "bookmarks")).isEqualTo("[]");
        String initial = read(token, "bookmarks");
        tx.executeWithoutResult(status -> {
            places.updateSpaceTag(placeId, "INDOOR", LocalDateTime.now());
            status.setRollbackOnly();
        });
        assertHit(token, "bookmarks", initial);
        places.updateSpaceTag(placeId, "OUTDOOR", LocalDateTime.now());
        assertThat(read(token, "bookmarks")).contains("OUTDOOR");
        assertHit(other, "bookmarks", "[]");
        assertThat(places.repairMissingLocation(placeId, 12.5, 20.0, "Region")).isOne();
        assertThat(read(token, "bookmarks")).contains("\"latitude\":12.5");
        assertHit(other, "bookmarks", "[]");
    }

    @Test
    void 편집자의_캐시된_여행_목록에_참여와_다른_멤버의_프로필과_새_제목이_반영된다() throws Exception {
        User owner = users.save(new User("google", "cache-share-owner", "Owner", null));
        User editor = users.save(new User("google", "cache-share-editor", "Editor", null));
        String token = jwt.issue(editor);
        Trip trip = seedTrip(owner);
        String invite = shareLinks.createOrGet(trip, owner).link().getToken();
        assertHit(token, "trips", read(token, "trips"));
        memberships.join(invite, editor);
        String shared = read(token, "trips");
        assertThat(shared).contains("First trip", "Owner", "EDITOR");
        assertHit(token, "trips", shared);

        User newcomer = users.save(new User("google", "cache-share-new", "New member", null));
        memberships.join(invite, newcomer);
        String joined = read(token, "trips");
        assertThat(joined).contains("New member", "\"memberCount\":3");
        assertHit(token, "trips", joined);
        profiles.upsert(new OAuth2UserInfo("google", "cache-share-owner", "New nickname", "/new-profile"));
        String renamed = read(token, "trips");
        assertThat(renamed).contains("New nickname", "/new-profile");
        assertHit(token, "trips", renamed);

        write(jwt.issue(owner), patch("/api/trips/" + trip.getId() + "/title"), "{\"title\":\"Shared title\"}", 200);
        assertThat(read(token, "trips")).contains("Shared title").doesNotContain("First trip");
    }

    @Test
    void 초기화하지_않은_일차로_장소를_추가하고_교체하고_지우면_편집자의_장소수와_지역이_갱신된다() throws Exception {
        User owner = users.save(new User("google", "cache-stop-owner", "Owner", null));
        User editor = users.save(new User("google", "cache-stop-editor", "Editor", null));
        Trip trip = seedTrip(owner);
        trip.getMembers().add(new TripMember(editor, TripMember.Role.EDITOR));
        trips.save(trip);
        Long dayId = tx.execute(status -> {
            Itinerary day = new Itinerary(trip, 1, trip.getStartDate());
            em.persist(day);
            return day.getId();
        });
        String token = jwt.issue(editor);
        String empty = read(token, "trips");
        assertThat(empty).contains("\"placeCount\":0", "\"regions\":[]");
        assertHit(token, "trips", empty);
        Long stopId = tx.execute(status -> {
            Itinerary day = em.getReference(Itinerary.class, dayId);
            TripPlace stop = new TripPlace(day, "First", "Busan", "cafe", 1.0, 1.0,
                    null, null, 1, PlaceSource.NORMAL, null);
            assertThat(Hibernate.isInitialized(day)).isFalse();
            em.persist(stop);
            em.flush();
            assertThat(Hibernate.isInitialized(day)).isFalse();
            return stop.getId();
        });
        String added = read(token, "trips");
        assertThat(added).contains("\"placeCount\":1", "\"regions\":[\"Busan\"]");
        assertHit(token, "trips", added);
        Place replacement = places.save(new Place("cache-replacement", "New", "cafe", 4.0, 1, null, 1.0, 1.0, null));
        assertThat(tripService.replacePlace(owner, stopId, replacement.getGooglePlaceId())).isPresent();
        String replaced = read(token, "trips");
        assertThat(replaced).contains("\"placeCount\":1", "\"regions\":[]");
        assertHit(token, "trips", replaced);
        assertThat(tripService.removePlace(owner, stopId)).isTrue();
        assertThat(read(token, "trips")).contains("\"placeCount\":0", "\"regions\":[]");
    }

    @Test
    void 북마크_조회가_끝나기_전에_공간_태그를_커밋하면_오래된_결과를_재사용하지_않는다() throws Exception {
        User owner = users.save(new User("google", "cache-pending-bookmark", "Owner", null));
        Long placeId = seedBookmark(owner).getPlace().getId();
        List<BookmarkController.BookmarkResponse> old = reads.bookmarks(owner.getId(), () -> {
            List<BookmarkController.BookmarkResponse> snapshot = bookmarks.findByUserIdOrderByCreatedAtDesc(owner.getId())
                    .stream().map(BookmarkController.BookmarkResponse::from).toList();
            places.updateSpaceTag(placeId, "INDOOR", LocalDateTime.now());
            return snapshot;
        });
        assertThat(old).singleElement().satisfies(row -> assertThat(row.space()).isNull());
        String token = jwt.issue(owner);
        String current = read(token, "bookmarks");
        assertThat(current).contains("\"space\":\"INDOOR\"");
        assertHit(token, "bookmarks", current);
    }

    @Test
    void Place의_북마크_표시_속성을_하나씩_flush하면_다음_응답에_반영된다() throws Exception {
        User owner = users.save(new User("google", "cache-flush", "Owner", null));
        Long placeId = seedBookmark(owner).getPlace().getId();
        String token = jwt.issue(owner);
        record Change(String entityField, String jsonField, Object value) {}
        String current = read(token, "bookmarks");
        for (Change change : List.of(
                new Change("name", "placeName", "Renamed"), new Change("category", "category", "park"),
                new Change("googlePlaceId", "googlePlaceId", "cache-new-id"), new Change("mood", "mood", "quiet"),
                new Change("space", "space", "INDOOR"), new Change("latitude", "latitude", 2.5),
                new Change("longitude", "longitude", 3.5), new Change("address", "address", "New address"))) {
            assertHit(token, "bookmarks", current);
            tx.executeWithoutResult(status -> ReflectionTestUtils.setField(em.find(Place.class, placeId),
                    change.entityField(), change.value()));
            current = read(token, "bookmarks");
            assertThat(json.readTree(current).get(0).get(change.jsonField()))
                    .as(change.entityField()).isEqualTo(json.valueToTree(change.value()));
        }
    }

    private Trip seedTrip(User user) {
        LocalDate day = LocalDate.of(2026, 1, 1);
        return trips.save(new Trip(user, "First trip", day, day));
    }

    private Bookmark seedBookmark(User user) {
        Place place = places.save(new Place("cache-" + user.getId(), "First bookmark", "cafe", 4.0, 1, null, 1.0, 1.0, "Region"));
        return bookmarks.save(new Bookmark(user, place));
    }

    private SavedPlace seedPlace(User user) {
        ProcessingJob job = jobs.save(new ProcessingJob(user, "https://example.test/video", SourcePlatform.YOUTUBE));
        return saved.save(new SavedPlace(job, user, "First stop", "Region", "cafe", 1.0, 1.0));
    }
}
