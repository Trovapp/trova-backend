package com.trova.backend.controller;

import com.trova.backend.entity.AdminActionLog;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.User;
import com.trova.backend.repository.AdminActionLogRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.support.AdminWebAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 사용자 찾기·상세 — 닉네임은 가리고, 로그인 고유번호·프로필 사진은 내보내지 않고, 열람은 기록한다(ADR 0014). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.admin.users=google:users-admin")
class AdminUsersControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProcessingJobRepository jobRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private TripDraftRepository draftRepository;

    @Autowired
    private AdminActionLogRepository logRepository;

    private RequestPostProcessor admin() {
        return AdminWebAuth.adminWebToken(jwtService, userRepository, "users-admin");
    }

    private User newUser(String nickname) {
        return userRepository.save(new User("kakao", "users-" + UUID.randomUUID(), nickname, "https://img/p.png"));
    }

    private AdminActionLog latestSearchLog() {
        Long adminId = userRepository.findByProviderAndProviderUserId("google", "users-admin").orElseThrow().getId();
        return logRepository.findTop50ByOrderByIdDesc().stream()
                .filter(l -> AdminActionLog.ADMIN_USER_SEARCH.equals(l.getAction()) && adminId.equals(l.getActorUserId()))
                .findFirst().orElseThrow();
    }

    @Test
    void 숫자로_찾으면_그_번호의_사용자_한_명만_가려서_준다() throws Exception {
        User user = newUser("김태형");
        String body = mockMvc.perform(get("/api/admin/users").param("q", String.valueOf(user.getId())).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(1))
                .andExpect(jsonPath("$.users[0].id").value(user.getId()))
                .andExpect(jsonPath("$.users[0].nickname").value("김**"))
                .andExpect(jsonPath("$.users[0].provider").value("kakao"))
                .andExpect(jsonPath("$.users[0].suspended").value(false))
                .andExpect(jsonPath("$.users[0].jobs").value(0))
                .andExpect(jsonPath("$.users[0].trips").value(0))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(user.getProviderUserId()).doesNotContain("김태형")
                .doesNotContain("providerUserId").doesNotContain("profileImageUrl").doesNotContain("img/p.png");
    }

    @Test
    void 검색_기록에는_검색어_없이_결과_수만_남는다() throws Exception {
        User user = newUser("기록대상");
        mockMvc.perform(get("/api/admin/users").param("q", String.valueOf(user.getId())).with(admin()))
                .andExpect(status().isOk());

        AdminActionLog log = latestSearchLog();
        assertThat(log.getResult()).isEqualTo("결과 1명");
        assertThat(log.getReason()).isNull();
        assertThat(log.getTargetId()).isNull();
        assertThat(log.getTargetUserId()).isNull();
    }

    @Test
    void 링크로_찾으면_그_링크를_넣은_사용자를_찾는다() throws Exception {
        User user = newUser("링크주인");
        String url = "https://youtu.be/" + UUID.randomUUID();
        jobRepository.save(new ProcessingJob(user, url, SourcePlatform.YOUTUBE));
        jobRepository.save(new ProcessingJob(user, url, SourcePlatform.YOUTUBE));

        mockMvc.perform(get("/api/admin/users").param("q", url).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(1))
                .andExpect(jsonPath("$.users[0].id").value(user.getId()))
                .andExpect(jsonPath("$.users[0].jobs").value(2));
    }

    @Test
    void 닉네임이나_여행_이름_일부로_찾고_대소문자를_가리지_않는다() throws Exception {
        String token = UUID.randomUUID().toString().substring(0, 8);
        User byNickname = newUser("Trova" + token + "님");
        User byTrip = newUser("다른이름");
        tripRepository.save(new Trip(byTrip, "부산 TRIP" + token.toUpperCase(), null, null));

        mockMvc.perform(get("/api/admin/users").param("q", "trova" + token.toUpperCase()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(1))
                .andExpect(jsonPath("$.users[0].id").value(byNickname.getId()));

        mockMvc.perform(get("/api/admin/users").param("q", "trip" + token).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(1))
                .andExpect(jsonPath("$.users[0].id").value(byTrip.getId()))
                .andExpect(jsonPath("$.users[0].trips").value(1));
    }

    @Test
    void 검색어의_퍼센트와_밑줄은_글자_그대로_찾는다() throws Exception {
        String token = UUID.randomUUID().toString().substring(0, 8);
        User user = newUser("할인50%" + token);

        mockMvc.perform(get("/api/admin/users").param("q", "50%" + token).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(1))
                .andExpect(jsonPath("$.users[0].id").value(user.getId()));

        mockMvc.perform(get("/api/admin/users").param("q", "5_%" + token).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(0));
    }

    @Test
    void 검색어가_비면_최근_가입_사용자를_준다() throws Exception {
        RequestPostProcessor admin = admin(); // 관리자 계정이 먼저 만들어지도록
        User user = newUser("최근가입");
        mockMvc.perform(get("/api/admin/users").param("q", " ").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[0].id").value(user.getId()));
    }

    @Test
    void 상세는_가린_닉네임과_건수를_주고_열람을_기록한다() throws Exception {
        User user = userRepository.save(new User("kakao", "9876543210", "김태형", "https://img/p.png"));
        String body = mockMvc.perform(get("/api/admin/users/" + user.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("김**"))
                .andExpect(jsonPath("$.provider").value("kakao"))
                .andExpect(jsonPath("$.counts.trips").value(0))
                .andExpect(jsonPath("$.admin").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("9876543210").doesNotContain("김태형").doesNotContain("img/p.png")
                .doesNotContain("providerUserId").doesNotContain("profileImageUrl");
        assertThat(logRepository.findTop50ByTargetUserIdOrderByIdDesc(user.getId()))
                .extracting(AdminActionLog::getAction).containsExactly(AdminActionLog.ADMIN_USER_VIEW);
        AdminActionLog log = logRepository.findTop50ByTargetUserIdOrderByIdDesc(user.getId()).get(0);
        assertThat(log.getTargetId()).isEqualTo(user.getId());
    }

    @Test
    void 상세는_최근_작업과_다시_실행할_수_있는지_여행과_자동_초안을_준다() throws Exception {
        User user = newUser("상세대상");
        ProcessingJob quota = new ProcessingJob(user, "https://youtu.be/q", SourcePlatform.YOUTUBE);
        quota.markFailed(ProcessingJob.AI_QUOTA_MESSAGE);
        jobRepository.save(quota);
        ProcessingJob noPlaces = new ProcessingJob(user, "https://youtu.be/n", SourcePlatform.YOUTUBE);
        noPlaces.markFailed(ProcessingJob.NO_PLACES_MESSAGE);
        jobRepository.save(noPlaces);
        ProcessingJob done = new ProcessingJob(user, "https://youtu.be/d", SourcePlatform.YOUTUBE);
        done.markDone();
        jobRepository.save(done);
        Trip trip = tripRepository.save(new Trip(user, "부산 여행", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)));
        TripDraft auto = draftRepository.save(TripDraft.auto(user, List.of(done.getId()), "자동"));
        draftRepository.save(new TripDraft(user, List.of(done.getId()), "직접"));

        mockMvc.perform(get("/api/admin/users/" + user.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.jobs").value(3))
                .andExpect(jsonPath("$.counts.trips").value(1))
                .andExpect(jsonPath("$.counts.drafts").value(2))
                .andExpect(jsonPath("$.counts.savedPlaces").value(0))
                .andExpect(jsonPath("$.suspendedAt").doesNotExist())
                .andExpect(jsonPath("$.lastActiveAt").isNotEmpty())
                .andExpect(jsonPath("$.recentJobs.length()").value(3))
                .andExpect(jsonPath("$.recentJobs[0].id").value(done.getId()))
                .andExpect(jsonPath("$.recentJobs[0].status").value("DONE"))
                .andExpect(jsonPath("$.recentJobs[0].retryable").value(false))
                .andExpect(jsonPath("$.recentJobs[1].failureReason").value("NO_PLACES"))
                .andExpect(jsonPath("$.recentJobs[1].retryable").value(false))
                .andExpect(jsonPath("$.recentJobs[2].failureReason").value("AI_QUOTA"))
                .andExpect(jsonPath("$.recentJobs[2].retryable").value(true))
                .andExpect(jsonPath("$.trips[0].id").value(trip.getId()))
                .andExpect(jsonPath("$.trips[0].title").value("부산 여행"))
                .andExpect(jsonPath("$.trips[0].startDate").value("2026-11-01"))
                .andExpect(jsonPath("$.autoDrafts.length()").value(1))
                .andExpect(jsonPath("$.autoDrafts[0].id").value(auto.getId()))
                .andExpect(jsonPath("$.autoDrafts[0].dismissed").value(false));
    }

    @Test
    void 같은_링크로_더_최근_작업이_있으면_예전_실패_작업은_다시_실행할_수_없다고_보여준다() throws Exception {
        User user = newUser("재실행끝");
        String url = "https://www.youtube.com/watch?v=" + UUID.randomUUID().toString().substring(0, 11);
        ProcessingJob old = new ProcessingJob(user, url, SourcePlatform.YOUTUBE);
        old.markFailed(ProcessingJob.AI_QUOTA_MESSAGE);
        jobRepository.save(old);
        ProcessingJob newer = new ProcessingJob(user, url, SourcePlatform.YOUTUBE);
        newer.markDone();
        jobRepository.save(newer);

        mockMvc.perform(get("/api/admin/users/" + user.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentJobs[1].id").value(old.getId()))
                .andExpect(jsonPath("$.recentJobs[1].failureReason").value("AI_QUOTA"))
                .andExpect(jsonPath("$.recentJobs[1].retryable").value(false));
    }

    @Test
    void 없는_번호면_404이고_열람_기록을_남기지_않는다() throws Exception {
        long missing = Long.MAX_VALUE - 7;
        mockMvc.perform(get("/api/admin/users/" + missing).with(admin()))
                .andExpect(status().isNotFound());
        assertThat(logRepository.findTop50ByTargetUserIdOrderByIdDesc(missing)).isEmpty();
    }
}
