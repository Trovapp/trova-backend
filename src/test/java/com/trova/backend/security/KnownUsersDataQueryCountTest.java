package com.trova.backend.security;

import com.trova.backend.entity.Bookmark;
import com.trova.backend.entity.BookmarkFolder;
import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Notification;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.BookmarkFolderRepository;
import com.trova.backend.repository.BookmarkRepository;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.NotificationRepository;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 데이터가 있는 JWT 사용자로 조회 API 7개를 부른다(ADR 0036, PR #333 리뷰).
 * 운영처럼 요청마다 트랜잭션이 없는 상태를 보려고 테스트 트랜잭션을 쓰지 않는다(데이터는 커밋된다).
 * - 기억된 사용자는 기억이 없을 때보다 DB 문장이 정확히 1개(존재 확인) 적다 — 데이터가 있어도 사용자 확인을 건너뛴다.
 * - 트랜잭션을 뺀 여행 목록도 지역까지 내려온다(지연 로딩 예외 없음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class KnownUsersDataQueryCountTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private KnownUsers knownUsers;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private SavedPlaceRepository savedPlaceRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private ItineraryRepository itineraryRepository;

    @Autowired
    private TripPlaceRepository tripPlaceRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private BookmarkRepository bookmarkRepository;

    @Autowired
    private BookmarkFolderRepository bookmarkFolderRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private TripDraftRepository tripDraftRepository;

    /** 엔드포인트마다 응답에 있어야 하는 글자 — 빈 목록이 아니라 실제 데이터가 내려왔는지 본다. */
    private Map<String, String> seedData(User user) {
        ProcessingJob done = new ProcessingJob(user, "https://youtu.be/known-users-done", SourcePlatform.YOUTUBE);
        done.markDone();
        processingJobRepository.save(done);
        savedPlaceRepository.save(new SavedPlace(done, user, "기억카페", "제주", "cafe", 33.4, 126.5));

        ProcessingJob pending = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/known-users-pending", SourcePlatform.YOUTUBE));

        LocalDate start = LocalDate.now().plusDays(30); // 지난 일정의 알림은 목록에서 빠지므로 앞으로의 날짜
        Trip trip = tripRepository.save(new Trip(user, "기억여행", start, start));
        Itinerary day1 = itineraryRepository.save(new Itinerary(trip, 1, start));
        TripPlace tripPlace = tripPlaceRepository.save(new TripPlace(day1, "여행장소", "서귀포", "cafe",
                33.2, 126.5, null, null, 0, PlaceSource.NORMAL, null));

        Place place = placeRepository.save(new Place("known-users-gpid", "찜장소", "cafe", 4.5, 10, null,
                33.4, 126.5, "제주시"));
        bookmarkRepository.save(new Bookmark(user, place));
        bookmarkFolderRepository.save(new BookmarkFolder(user, "기억폴더", "#ff0000"));
        notificationRepository.save(new Notification(user, day1, "비소식", "우산 챙기세요", 0.7, tripPlace.getId()));
        TripDraft draft = tripDraftRepository.save(TripDraft.auto(user, List.of(done.getId()), "자동초안"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("/api/places", "기억카페");
        expected.put("/api/places/pending", String.valueOf(pending.getId()));
        expected.put("/api/trips", "서귀포");
        expected.put("/api/bookmarks", "찜장소");
        expected.put("/api/bookmarks/folders", "기억폴더");
        expected.put("/api/notifications", "비소식");
        expected.put("/api/trip-drafts/auto", "\"draftId\":" + draft.getId());
        return expected;
    }

    private long statementsFor(String path, String bearer, String mustContain) throws Exception {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(mustContain)));
        return stats.getPrepareStatementCount();
    }

    @Test
    void 데이터가_있어도_기억된_사용자는_존재_확인_문장_하나를_건너뛴다() throws Exception {
        User user = userRepository.save(new User("google", "known-users-data", "데이터있음", null));
        Map<String, String> expected = seedData(user);
        String bearer = "Bearer " + jwtService.issue(user);

        for (Map.Entry<String, String> endpoint : expected.entrySet()) {
            String path = endpoint.getKey();
            statementsFor(path, bearer, endpoint.getValue()); // 예열(처음 한 번의 준비 비용 제외)

            knownUsers.remove(user.getId());
            long unknown = statementsFor(path, bearer, endpoint.getValue());
            long known = statementsFor(path, bearer, endpoint.getValue());

            System.out.println("[ADR 0036] " + path + " 문장 수(데이터 있음): 기억 없음=" + unknown + ", 기억됨=" + known);
            assertThat(known).as(path).isEqualTo(unknown - 1);
        }
    }
}
