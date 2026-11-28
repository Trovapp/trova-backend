package com.trova.backend.controller;

import com.trova.backend.entity.Bookmark;
import com.trova.backend.entity.BookmarkFolder;
import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Notification;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.BookmarkFolderRepository;
import com.trova.backend.repository.BookmarkRepository;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.NotificationRepository;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 찜·폴더·알림 목록이 데이터 개수와 상관없이 같은 수의 SQL로 끝나는지 본다(#335).
 * 고치기 전(로컬 H2, 데이터 1/5/10개): 찜 3/11/21, 폴더 3/7/12, 알림 2/6/11.
 * 운영처럼 요청마다 트랜잭션이 없는 상태를 보려고 테스트 트랜잭션을 쓰지 않는다(데이터는 커밋된다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ListQueryCountTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

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
    private BookmarkRepository bookmarkRepository;

    @Autowired
    private BookmarkFolderRepository bookmarkFolderRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    /** 찜·폴더·알림을 n개씩 만든다. 찜은 서로 다른 장소·폴더, 알림은 서로 다른 일정을 가리킨다. */
    private String userWith(int n, String tag) {
        User user = userRepository.save(new User("google", "list-count-" + tag, "목록", null));
        LocalDate start = LocalDate.now().plusDays(30); // 지난 일정의 알림은 목록에서 빠지므로 앞으로의 날짜
        Trip trip = tripRepository.save(new Trip(user, "목록여행", start, start.plusDays(n)));
        for (int i = 0; i < n; i++) {
            Itinerary day = itineraryRepository.save(new Itinerary(trip, i + 1, start.plusDays(i)));
            TripPlace tripPlace = tripPlaceRepository.save(new TripPlace(day, "장소" + i, "서귀포", "cafe",
                    33.2, 126.5, null, null, 0, PlaceSource.NORMAL, null));
            notificationRepository.save(new Notification(user, day, "비소식" + i, "우산", 0.7, tripPlace.getId()));

            Place place = placeRepository.save(new Place("list-count-" + tag + "-" + i, "찜장소" + i, "cafe",
                    4.5, 10, null, 33.4, 126.5, "제주시"));
            BookmarkFolder folder = bookmarkFolderRepository.save(new BookmarkFolder(user, "폴더" + i, "#ff0000"));
            Bookmark bookmark = new Bookmark(user, place);
            bookmark.applyFolder(folder);
            bookmarkRepository.save(bookmark);
        }
        // 찜이 없는 폴더도 개수 0으로 내려와야 한다.
        bookmarkFolderRepository.save(new BookmarkFolder(user, "빈폴더", "#00ff00"));
        return "Bearer " + jwtService.issue(user);
    }

    private long statements(String path, String bearer, int expectedSize) throws Exception {
        // JWT 사용자의 존재 확인만 예열하고, 찜 목록의 N+1 단언은 캐시 미스에서 잰다.
        String warmup = path.equals("/api/bookmarks") ? "/api/bookmarks/folders" : path;
        mockMvc.perform(get(warmup).header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk());
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(expectedSize)));
        return stats.getPrepareStatementCount();
    }

    @Test
    void 찜_폴더_알림_목록은_데이터가_늘어도_SQL_수가_같다() throws Exception {
        String one = userWith(1, "one");
        String ten = userWith(10, "ten");

        for (String path : new String[] {"/api/bookmarks", "/api/bookmarks/folders", "/api/notifications"}) {
            int extraFolder = path.endsWith("folders") ? 1 : 0;
            long withOne = statements(path, one, 1 + extraFolder);
            long withTen = statements(path, ten, 10 + extraFolder);
            System.out.println("[#335] " + path + " SQL 수: 1개=" + withOne + ", 10개=" + withTen);
            assertThat(withTen).as(path).isEqualTo(withOne);
        }
    }

    @Test
    void 한_쿼리로_읽어도_응답_내용은_그대로다() throws Exception {
        String bearer = userWith(2, "content");

        mockMvc.perform(get("/api/bookmarks").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].placeName").exists())
                .andExpect(jsonPath("$[0].folderId").isNumber())
                .andExpect(content().string(containsString("찜장소1")));
        mockMvc.perform(get("/api/bookmarks/folders").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '빈폴더')].placeCount").value(0))
                .andExpect(jsonPath("$[?(@.name == '폴더0')].placeCount").value(1));
        mockMvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tripId").isNumber())
                .andExpect(content().string(containsString("비소식1")));
    }
}
