package com.trova.backend.controller;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Notification;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.NotificationRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TripRepository tripRepository;
    @Autowired private ItineraryRepository itineraryRepository;
    @Autowired private NotificationRepository notificationRepository;

    private User me;
    private Trip trip;

    @BeforeEach
    void setUp() {
        me = userRepository.save(new User("google", "notif1", "알림유저", null));
        trip = tripRepository.save(new Trip(me, "테스트 여행", LocalDate.now().minusDays(3), LocalDate.now().plusDays(3)));
    }

    private RequestPostProcessor loginAs(String sub, String name) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .build();
        return oauth2Login()
                .clientRegistration(registration)
                .attributes(attrs -> {
                    attrs.put("sub", sub);
                    attrs.put("name", name);
                    attrs.put("picture", "https://example.com/p.jpg");
                });
    }

    private Notification notificationForDate(LocalDate date) {
        Itinerary itinerary = itineraryRepository.save(new Itinerary(trip, 1, date));
        return notificationRepository.save(new Notification(
                me, itinerary, "비 소식이 있어요", "강수확률 80%예요", 80.0, 1L));
    }

    @Test
    void 오늘_이후_일정의_알림은_목록에_보인다() throws Exception {
        notificationForDate(LocalDate.now());

        mockMvc.perform(get("/api/notifications").with(loginAs("notif1", "알림유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void 지난_날짜_일정의_알림은_목록에서_빠진다() throws Exception {
        notificationForDate(LocalDate.now().minusDays(1));

        mockMvc.perform(get("/api/notifications").with(loginAs("notif1", "알림유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void dismiss하면_목록에서_사라진다() throws Exception {
        Notification notification = notificationForDate(LocalDate.now());

        mockMvc.perform(post("/api/notifications/" + notification.getId() + "/dismiss").with(loginAs("notif1", "알림유저")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/notifications").with(loginAs("notif1", "알림유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
