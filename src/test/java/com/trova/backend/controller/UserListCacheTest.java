package com.trova.backend.controller;

import com.trova.backend.entity.Bookmark;
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
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class UserListCacheTest {
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private BookmarkRepository bookmarks;
    @Autowired private BookmarkFolderRepository folders;
    @Autowired private SavedPlaceRepository saved;
    @Autowired private ProcessingJobRepository jobs;
    @Autowired private PlaceRepository places;
    @Autowired private EntityManagerFactory emf;

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
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        for (String list : List.of("places", "bookmarks", "trips")) {
            String initial = read(token, list);
            assertThat(initial).isNotEqualTo("[]");
            stats.clear();
            assertThat(read(token, list)).isEqualTo(initial);
            assertThat(stats.getPrepareStatementCount()).as(list).isZero();
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
