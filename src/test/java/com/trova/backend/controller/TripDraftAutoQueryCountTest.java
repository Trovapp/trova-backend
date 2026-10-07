package com.trova.backend.controller;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 자동 초안 목록(#139)의 DB 쿼리 수가 초안 수와 상관없이 같은지 잰다. 고치기 전에는 초안마다 사용자 영상 전체·영상 장소·
 * 여행 장소를 다시 읽어 초안이 늘수록 쿼리가 늘었다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class TripDraftAutoQueryCountTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private TripDraftRepository tripDraftRepository;

    @Autowired
    private SavedPlaceRepository savedPlaceRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private ItineraryRepository itineraryRepository;

    @Autowired
    private TripPlaceRepository tripPlaceRepository;

    @Autowired
    private EntityManager entityManager;

    private ClientRegistration googleRegistration() {
        return ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .build();
    }

    private RequestPostProcessor loginAs(String sub, String name) {
        return oauth2Login()
                .clientRegistration(googleRegistration())
                .attributes(attrs -> {
                    attrs.put("sub", sub);
                    attrs.put("name", name);
                    attrs.put("picture", "https://example.com/p.jpg");
                });
    }

    private ProcessingJob videoJob(User user, String url, String title) {
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(user, url, SourcePlatform.YOUTUBE));
        job.setTitle(title);
        return processingJobRepository.save(job);
    }

    private long queriesForAutoList(String sub, String name) throws Exception {
        entityManager.flush();
        entityManager.clear();
        Statistics stats = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        mockMvc.perform(get("/api/trip-drafts/auto").with(loginAs(sub, name))).andExpect(status().isOk());
        return stats.getPrepareStatementCount();
    }

    private void readyAutoDraft(User user, String url, String title) {
        ProcessingJob job = videoJob(user, url, title);
        savedPlaceRepository.save(new SavedPlace(job, user, title + " 장소", "서울", "attraction", 37.5, 127.0));
        TripDraft draft = TripDraft.auto(user, List.of(job.getId()), "당일치기");
        draft.markReady("{}", "{}");
        tripDraftRepository.save(draft);
    }

    @Test
    void 자동초안_목록의_쿼리_수는_초안_수와_상관없이_같다() throws Exception {
        User one = userRepository.save(new User("google", "autoq1", "쿼리유저1", null));
        readyAutoDraft(one, "https://youtu.be/AutoQ100", "영상0");
        long withOne = queriesForAutoList("autoq1", "쿼리유저1");

        User many = userRepository.save(new User("google", "autoq2", "쿼리유저2", null));
        for (int i = 0; i < 8; i++) {
            readyAutoDraft(many, "https://youtu.be/AutoQ2" + i, "영상" + i);
        }
        long withEight = queriesForAutoList("autoq2", "쿼리유저2");

        System.out.println("[#139] 자동 초안 목록 쿼리 수: 초안 1개=" + withOne + ", 초안 8개=" + withEight);
        assertThat(withEight).isEqualTo(withOne);
    }
}
