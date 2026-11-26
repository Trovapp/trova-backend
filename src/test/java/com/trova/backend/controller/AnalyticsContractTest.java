package com.trova.backend.controller;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtAuthenticationToken;
import com.trova.backend.security.KnownUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.analytics.notice-ready=true")
@AutoConfigureMockMvc
@Transactional
class AnalyticsContractTest {
    private static final String PATH = "/api/me/analytics-consent";
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private ProcessingJobRepository jobs;
    @Autowired private TripRepository trips;
    @Autowired private JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired private KnownUsers knownUsers;

    @Test
    void 배치의_동의_세대가_현재_허용된_세대여야_한다() throws Exception {
        User user = user();
        mvc.perform(get(PATH).param("expectedRevision", "0").with(as(user))).andExpect(status().isConflict());
        String id = consent(user, 0, true, true).get("analyticsId").asText();
        mvc.perform(get(PATH).param("expectedRevision", "1").with(as(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.analyticsId").value(id));
        mvc.perform(delete(PATH).with(as(user))).andExpect(status().isNoContent());
        consent(user, 2, true, true);
        mvc.perform(get(PATH).param("expectedRevision", "1").with(as(user))).andExpect(status().isConflict());
        mvc.perform(get(PATH).param("expectedRevision", "").with(as(user))).andExpect(status().isBadRequest());
    }

    @Test
    void 결과와_동의_폴링에서_JWT_사용자_ID를_기억한다() throws Exception {
        User user = user();
        mvc.perform(get("/api/places/videos/0/analysis-result").with(as(user))).andExpect(status().isNotFound());
        assertThat(knownUsers.contains(user.getId())).isTrue();
        knownUsers.remove(user.getId());
        mvc.perform(get(PATH).with(as(user))).andExpect(status().isOk());
        assertThat(knownUsers.contains(user.getId())).isTrue();
    }

    private User user() {
        return users.save(new User("google", UUID.randomUUID().toString(), "테스트", null));
    }

    private RequestPostProcessor as(User user) {
        return authentication(new JwtAuthenticationToken(user.getId()));
    }

    private String body(long revision, boolean usage, boolean transfer) {
        return "{\"consentVersion\":\"analytics-2026-11-26-v1\",\"expectedRevision\":"
                + revision + ",\"usageConsent\":" + usage + ",\"transferConsent\":" + transfer + "}";
    }

    private JsonNode consent(User user, long revision, boolean usage, boolean transfer) throws Exception {
        return mapper.readTree(mvc.perform(put(PATH).with(as(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(revision, usage, transfer)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void 미동의는_기본_false이며_식별자를_노출하지_않는다() throws Exception {
        mvc.perform(get(PATH).with(as(user())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.usageConsent").value(false))
                .andExpect(jsonPath("$.transferConsent").value(false))
                .andExpect(jsonPath("$.analyticsId").isEmpty());
    }

    @Test
    void 미인증_조회_저장_철회는_401이다() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body(0, true, true)))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void CAS_충돌과_동일_재시도를_구분하고_철회_후_새_UUID를_발급한다() throws Exception {
        User user = user();
        JsonNode first = consent(user, 0, true, true);
        String id = first.get("analyticsId").asText();
        assertThat(UUID.fromString(id).version()).isEqualTo(4);
        assertThat(first.get("revision").asLong()).isEqualTo(1);
        assertThat(consent(user, 0, true, true)).isEqualTo(first);
        mvc.perform(put(PATH).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(body(0, false, false)))
                .andExpect(status().isConflict());
        mvc.perform(delete(PATH).with(as(user))).andExpect(status().isNoContent());
        mvc.perform(delete(PATH).with(as(user))).andExpect(status().isNoContent());
        mvc.perform(get(PATH).with(as(user))).andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.analyticsId").isEmpty());
        mvc.perform(put(PATH).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(body(0, true, true)))
                .andExpect(status().isConflict());
        assertThat(consent(user, 2, true, true).get("analyticsId").asText()).isNotEqualTo(id);
        assertThat(jdbc.queryForObject("select count(*) from analytics_deletion_jobs where analytics_id = ? and status = 'PENDING'",
                Long.class, UUID.fromString(id))).isEqualTo(1);
    }

    @Test
    void 일부_동의도_이전_UUID를_폐기하고_다른_계정에_영향을_주지_않는다() throws Exception {
        User me = user();
        User other = user();
        UUID oldId = UUID.fromString(consent(me, 0, true, true).get("analyticsId").asText());
        assertThat(consent(me, 1, true, false).get("analyticsId").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from analytics_deletion_jobs where analytics_id = ? and status = 'PENDING'",
                Long.class, oldId)).isEqualTo(1);
        mvc.perform(get(PATH).with(as(other))).andExpect(jsonPath("$.revision").value(0));
    }

    @Test
    void 버전과_누락된_필드를_거절하고_가입_판정_불가시_생략한다() throws Exception {
        User user = user();
        mvc.perform(put(PATH).with(as(user)).contentType(MediaType.APPLICATION_JSON)
                .content(body(0, true, true).replace("analytics-2026-11-26-v1", "old")))
                .andExpect(status().isBadRequest());
        mvc.perform(put(PATH).with(as(user)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(consent(user, 0, true, true).has("signup")).isFalse();
    }

    @Test
    void 탈퇴하면_연결을_지우기_전에_삭제_작업을_남긴다() throws Exception {
        User user = user();
        String id = consent(user, 0, true, true).get("analyticsId").asText();
        mvc.perform(delete("/api/users/me").with(as(user))).andExpect(status().isNoContent());
        users.flush();
        assertThat(jdbc.queryForObject("select count(*) from analytics_consents where user_id = ?", Long.class, user.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from analytics_deletion_jobs where analytics_id = ? and status = 'PENDING'",
                Long.class, UUID.fromString(id))).isEqualTo(1);
    }

    @Test
    void 분석_저장완료와_0개_결과를_확인하고_일정_실패와_구분한다() throws Exception {
        User user = user();
        ProcessingJob job = jobs.save(new ProcessingJob(user, "https://youtu.be/test", SourcePlatform.YOUTUBE));
        String path = "/api/places/videos/" + job.getId() + "/analysis-result";
        mvc.perform(get(path).with(as(user))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        job.markProcessing();
        mvc.perform(get(path).with(as(user))).andExpect(jsonPath("$.status").value("PROCESSING"));
        job.markAnalysisDone();
        job.markFailed("일정 생성 실패");
        mvc.perform(get(path).with(as(user))).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.placeCount").value(0)).andExpect(jsonPath("$.failureReason").isEmpty());
        mvc.perform(get(path).with(as(user()))).andExpect(status().isNotFound());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        jobs.delete(job);
        mvc.perform(get(path).with(as(user))).andExpect(status().isNotFound());
    }

    @Test
    void 실패_원문은_전송하지_않고_허용한_코드만_반환한다() throws Exception {
        User user = user();
        ProcessingJob job = jobs.save(new ProcessingJob(user, "https://youtu.be/test", SourcePlatform.YOUTUBE));
        job.markAnalysisFailed(ProcessingJob.AI_QUOTA_MESSAGE);
        mvc.perform(get("/api/places/videos/" + job.getId() + "/analysis-result").with(as(user)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("AI_QUOTA"));
    }

    @Test
    void 공유_링크_회전은_신규_생성으로_집계하지_않는다() throws Exception {
        User owner = user();
        Trip trip = trips.save(new Trip(owner, "회전", null, null));
        String path = "/api/trips/" + trip.getId() + "/share-link";
        mvc.perform(post(path).with(as(owner))).andExpect(status().isCreated());
        mvc.perform(post(path + "/rotate").with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.newlyCreated").value(false))
                .andExpect(jsonPath("$.analyticsEventId").doesNotExist());
    }

    @Test
    void 이벤트_ID_컬럼을_생성하지_않는다() {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns "
                + "where lower(table_name) in ('trip_members', 'trip_share_links') "
                + "and lower(column_name) = 'analytics_event_id'", Long.class)).isZero();
    }

    @Test
    void 공유_생성과_참여는_신규_여부만_반환하고_이벤트_ID를_노출하지_않는다() throws Exception {
        User owner = user();
        User editor = user();
        Trip trip = trips.save(new Trip(owner, "테스트", null, null));
        String path = "/api/trips/" + trip.getId() + "/share-link";
        JsonNode created = mapper.readTree(mvc.perform(post(path).with(as(owner)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.newlyCreated").value(true))
                .andReturn().getResponse().getContentAsString());
        assertThat(created.has("analyticsEventId")).isFalse();
        mvc.perform(post(path).with(as(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.newlyCreated").value(false));
        String join = "/api/public/trips/" + created.get("token").asText() + "/join";
        JsonNode joined = mapper.readTree(mvc.perform(post(join).with(as(editor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.newlyJoined").value(true))
                .andReturn().getResponse().getContentAsString());
        assertThat(joined.has("analyticsEventId")).isFalse();
        mvc.perform(post(join).with(as(editor))).andExpect(status().isOk()).andExpect(jsonPath("$.newlyJoined").value(false));
        mvc.perform(post(path).with(as(user()))).andExpect(status().isForbidden());
    }
}
