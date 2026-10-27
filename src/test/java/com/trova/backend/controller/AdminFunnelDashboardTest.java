package com.trova.backend.controller;

import com.trova.backend.entity.Bookmark;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SignalType;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import com.trova.backend.entity.UserPreferenceSignal;
import com.trova.backend.repository.BookmarkRepository;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserPreferenceSignalRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.support.AdminWebAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 사용자 퍼널·기능 사용 지표(#237). 날짜를 정하려고 저장 뒤 created_at을 직접 고친다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.admin.users=google:funnel-admin")
class AdminFunnelDashboardTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private SavedPlaceRepository savedPlaceRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private UserPreferenceSignalRepository signalRepository;

    @Autowired
    private BookmarkRepository bookmarkRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtService jwtService;

    private User admin;
    private Place place;

    @BeforeEach
    void setUp() {
        // 테스트 DB에 다른 테스트가 남긴 사용자가 있을 수 있어, 모든 사용자를 아주 오래전 가입으로 미뤄 두고 시작한다.
        jdbcTemplate.update("update users set created_at = ?", LocalDateTime.of(2000, 1, 1, 0, 0));
        admin = userRepository.save(new User("google", "funnel-admin", "관리자", null));
        place = placeRepository.save(new Place("funnel-g1", "카페", "cafe", 4.5, 10, null, 37.5, 127.0, "서울"));
    }

    private RequestPostProcessor asAdmin() {
        return AdminWebAuth.adminWebToken(jwtService, userRepository, "funnel-admin");
    }

    private User user(String sub) {
        return userRepository.save(new User("google", sub, sub + "닉", null));
    }

    private ProcessingJob share(User user, boolean done) {
        ProcessingJob job = new ProcessingJob(user, "https://youtu.be/" + user.getProviderUserId(), SourcePlatform.YOUTUBE);
        if (done) {
            job.markDone();
        }
        return processingJobRepository.save(job);
    }

    private void setCreatedAt(String table, Long id, LocalDateTime at) {
        jdbcTemplate.update("update " + table + " set created_at = ? where id = ?", at, id);
    }

    @Test
    void 관리자가_아니면_퍼널을_볼_수_없다() throws Exception {
        user("funnel-normal");
        mockMvc.perform(get("/api/admin/dashboard/funnel")
                        .with(AdminWebAuth.adminWebToken(jwtService, userRepository, "funnel-normal")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 기간은_전체_7_14_30일만_받는다() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard/funnel?days=0").with(asAdmin())).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/dashboard/funnel?days=5").with(asAdmin())).andExpect(status().isBadRequest());
    }

    @Test
    void 퍼널은_앞_단계를_거친_사람만_다음_단계로_세고_관리자는_뺀다() throws Exception {
        User full = user("funnel-full");
        ProcessingJob job = share(full, true);
        savedPlaceRepository.save(new SavedPlace(job, full, "카페", "서울", "cafe", 37.5, 127.0));
        tripRepository.save(new Trip(full, "서울 여행", null, null));
        signalRepository.save(new UserPreferenceSignal(full, place, SignalType.ALTERNATIVE_REPLACED));

        share(user("funnel-share-only"), false);
        // 영상 공유 없이 여행만 만든 사람은 "영상 공유" 단계에서 빠지므로 뒤 단계에도 세지 않는다.
        tripRepository.save(new Trip(user("funnel-trip-only"), "직접 만든 여행", null, null));
        share(admin, true);

        mockMvc.perform(get("/api/admin/dashboard/funnel?days=7").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.steps[0].key").value("signup"))
                .andExpect(jsonPath("$.steps[0].users").value(3))
                .andExpect(jsonPath("$.steps[1].key").value("shared"))
                .andExpect(jsonPath("$.steps[1].users").value(2))
                .andExpect(jsonPath("$.steps[2].key").value("gotPlaces"))
                .andExpect(jsonPath("$.steps[2].users").value(1))
                .andExpect(jsonPath("$.steps[3].key").value("madeTrip"))
                .andExpect(jsonPath("$.steps[3].users").value(1))
                .andExpect(jsonPath("$.steps[4].key").value("refined"))
                .andExpect(jsonPath("$.steps[4].users").value(1));
    }

    @Test
    void 퍼널은_기간_안에_가입한_사람만_대상이고_전체_기간이면_모두_센다() throws Exception {
        User old = user("funnel-old");
        setCreatedAt("users", old.getId(), LocalDate.now().minusDays(40).atTime(9, 0));
        share(old, true);
        user("funnel-new");

        mockMvc.perform(get("/api/admin/dashboard/funnel?days=30").with(asAdmin()))
                .andExpect(jsonPath("$.steps[0].users").value(1))
                .andExpect(jsonPath("$.steps[1].users").value(0));
        // 전체 기간: setUp에서 미뤄 둔 다른 테스트 사용자까지 포함되므로 "최소"만 확인한다.
        mockMvc.perform(get("/api/admin/dashboard/funnel?days=0").with(asAdmin()))
                .andExpect(jsonPath("$.days").value(0))
                .andExpect(jsonPath("$.steps[1].users").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void 첫_영상_공유까지_걸린_시간은_중앙값을_분으로_준다() throws Exception {
        LocalDateTime base = LocalDateTime.now().minusDays(1).withNano(0);
        int[] minutes = {10, 30, 90};
        for (int i = 0; i < minutes.length; i++) {
            User u = user("funnel-first-" + i);
            setCreatedAt("users", u.getId(), base);
            setCreatedAt("processing_jobs", share(u, true).getId(), base.plusMinutes(minutes[i]));
            // 두 번째 공유는 첫 공유 시간에 영향을 주지 않는다.
            setCreatedAt("processing_jobs", share(u, true).getId(), base.plusMinutes(minutes[i] + 500));
        }

        mockMvc.perform(get("/api/admin/dashboard/funnel?days=7").with(asAdmin()))
                .andExpect(jsonPath("$.medianMinutesToFirstShare").value(30));
    }

    @Test
    void 기능별_사용은_기간_안의_사람_수와_횟수를_세고_관리자는_뺀다() throws Exception {
        User a = user("funnel-fa");
        User b = user("funnel-fb");
        share(a, true);
        share(a, false);
        share(b, true);
        ProcessingJob old = share(b, true);
        setCreatedAt("processing_jobs", old.getId(), LocalDate.now().minusDays(20).atTime(9, 0));
        share(admin, true);
        signalRepository.save(new UserPreferenceSignal(a, place, SignalType.ALTERNATIVE_REPLACED));
        signalRepository.save(new UserPreferenceSignal(a, place, SignalType.ALTERNATIVE_REPLACED));
        bookmarkRepository.save(new Bookmark(b, place));

        mockMvc.perform(get("/api/admin/dashboard/funnel?days=7").with(asAdmin()))
                .andExpect(jsonPath("$.features[0].key").value("shares"))
                .andExpect(jsonPath("$.features[0].users").value(2))
                .andExpect(jsonPath("$.features[0].count").value(3))
                .andExpect(jsonPath("$.features[0].details.done").value(2))
                .andExpect(jsonPath("$.features[?(@.key=='alternativeReplaced')].users").value(1))
                .andExpect(jsonPath("$.features[?(@.key=='alternativeReplaced')].count").value(2))
                .andExpect(jsonPath("$.features[?(@.key=='bookmarks')].users").value(1))
                .andExpect(jsonPath("$.features[?(@.key=='gapInserted')].count").value(0));
    }

    @Test
    void 사용자별_표는_마지막_활동이_최근인_순서로_도달_단계와_횟수를_준다() throws Exception {
        User quiet = user("funnel-quiet");
        User active = user("funnel-active");
        User idle = user("funnel-idle");
        setCreatedAt("processing_jobs", share(quiet, true).getId(), LocalDateTime.now().minusDays(3));
        share(active, true);
        tripRepository.save(new Trip(active, "여행", null, null));

        mockMvc.perform(get("/api/admin/dashboard/funnel?days=7").with(asAdmin()))
                .andExpect(jsonPath("$.users.length()").value(3))
                .andExpect(jsonPath("$.users[0].id").value(active.getId()))
                .andExpect(jsonPath("$.users[0].nickname").value("funnel-active닉"))
                .andExpect(jsonPath("$.users[0].shares").value(1))
                .andExpect(jsonPath("$.users[0].trips").value(1))
                .andExpect(jsonPath("$.users[0].reachedStep").value("shared"))
                .andExpect(jsonPath("$.users[1].id").value(quiet.getId()))
                .andExpect(jsonPath("$.users[2].id").value(idle.getId()))
                .andExpect(jsonPath("$.users[2].lastActiveAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.users[2].reachedStep").value("signup"));
    }
}
