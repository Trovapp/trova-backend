package com.trova.backend.controller;

import com.trova.backend.entity.*;
import com.trova.backend.recommendation.GooglePlacesApiClient;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.repository.*;
import com.trova.backend.service.BillingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 무료 + 여행 패스(#130) — 막기를 켠 상태에서 한도·구매·연장을 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"app.billing.enforce=true", "app.billing.allow-xcode-transactions=true"})
class BillingFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TripDraftRepository tripDraftRepository;
    @Autowired
    private UsageRecordRepository usageRecordRepository;
    @Autowired
    private TravelPassRepository travelPassRepository;

    @MockitoBean
    private GooglePlacesApiClient googlePlacesApiClient;
    @MockitoBean
    private PlaceEmbeddingService placeEmbeddingService;

    static RequestPostProcessor loginAs(String sub) {
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
            a.put("name", "결제유저");
            a.put("picture", "https://example.com/p.jpg");
        });
    }

    /** StoreKit 2 거래 모양의 JWS(서명은 확인하지 않는 Xcode 테스트 거래). */
    static String transaction(String environment, String transactionId, String appAccountToken, String productId) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"ES256\",\"x5c\":[]}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"transactionId\":\"" + transactionId + "\",\"productId\":\"" + productId
                + "\",\"bundleId\":\"com.trovapp.trova\",\"environment\":\"" + environment
                + "\",\"appAccountToken\":\"" + appAccountToken + "\"}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }

    private String body(String jws) {
        return "{\"signedTransaction\":\"" + jws + "\"}";
    }

    @Test
    void 처음엔_무료이고_남은_횟수를_준다() throws Exception {
        userRepository.save(new User("google", "bill-1", "결제유저", null));
        mockMvc.perform(get("/api/billing/status").with(loginAs("bill-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.enforced").value(true))
                .andExpect(jsonPath("$.usage.ANALYSIS.limit").value(20))
                .andExpect(jsonPath("$.usage.DRAFT.remaining").value(1))
                .andExpect(jsonPath("$.usage.ASSIST.limit").value(5))
                .andExpect(jsonPath("$.productId").value("com.trovapp.trova.travelpass30"));
    }

    @Test
    void 무료_초안과_비서를_다_쓰면_402로_구매를_안내한다() throws Exception {
        User me = userRepository.save(new User("google", "bill-2", "결제유저", null));
        tripDraftRepository.save(new TripDraft(me, List.of(1L), "2박 3일"));
        for (int i = 0; i < 5; i++) {
            usageRecordRepository.save(new UsageRecord(me, MeteredFeature.ASSIST, LocalDateTime.now()));
        }

        mockMvc.perform(post("/api/trip-drafts").with(loginAs("bill-2"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"jobIds\":[1],\"message\":\"2박 3일\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("PLAN_LIMIT"))
                .andExpect(jsonPath("$.feature").value("DRAFT"))
                .andExpect(jsonPath("$.onPass").value(false));
        mockMvc.perform(post("/api/trips/999999/replan").with(loginAs("bill-2"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"indoorOnly\":true}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.feature").value("ASSIST"));
    }

    @Test
    void 패스를_사면_30일이_붙고_같은_거래는_한_장만_다시_사면_이어서_연장된다() throws Exception {
        User me = userRepository.save(new User("google", "bill-3", "결제유저", null));
        String token = BillingService.appAccountToken(me).toString();
        tripDraftRepository.save(new TripDraft(me, List.of(1L), "2박 3일"));

        mockMvc.perform(post("/api/billing/apple-transactions").with(loginAs("bill-3")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(transaction("Xcode", "tx-1", token, "com.trovapp.trova.travelpass30"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("PASS"))
                .andExpect(jsonPath("$.usage.DRAFT.limit").value(10));
        mockMvc.perform(post("/api/billing/apple-transactions").with(loginAs("bill-3")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(transaction("Xcode", "tx-1", token, "com.trovapp.trova.travelpass30"))))
                .andExpect(status().isOk());
        assertThat(travelPassRepository.findAll()).filteredOn(p -> p.getUser().getId().equals(me.getId())).hasSize(1);

        mockMvc.perform(post("/api/billing/apple-transactions").with(loginAs("bill-3")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(transaction("Xcode", "tx-2", token, "com.trovapp.trova.travelpass30"))))
                .andExpect(status().isOk());
        TravelPass latest = travelPassRepository.findFirstByUserOrderByExpiresAtDesc(me).orElseThrow();
        TravelPass first = travelPassRepository.findByTransactionId("tx-1").orElseThrow();
        assertThat(latest.getStartsAt()).isEqualTo(first.getExpiresAt());
        assertThat(Duration.between(first.getStartsAt(), latest.getExpiresAt()).toDays()).isEqualTo(60);

        // 무료 초안은 이미 썼지만 패스(하루 10회)라 막히지 않는다 — 요청 자체가 잘못돼 다른 오류가 날 뿐 402는 아니다.
        int code = mockMvc.perform(post("/api/trip-drafts").with(loginAs("bill-3"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"jobIds\":[1],\"message\":\"2박 3일\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(code).isNotEqualTo(402);
    }

    @Test
    void 남의_거래나_실제_결제_거래_모르는_상품은_받지_않는다() throws Exception {
        User me = userRepository.save(new User("google", "bill-4", "결제유저", null));
        User other = userRepository.save(new User("google", "bill-4-other", "남", null));
        String mine = BillingService.appAccountToken(me).toString();

        mockMvc.perform(post("/api/billing/apple-transactions").with(loginAs("bill-4")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(transaction("Xcode", "tx-o", BillingService.appAccountToken(other).toString(),
                                "com.trovapp.trova.travelpass30"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/billing/apple-transactions").with(loginAs("bill-4")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(transaction("Sandbox", "tx-s", mine, "com.trovapp.trova.travelpass30"))))
                .andExpect(status().isNotImplemented());
        mockMvc.perform(post("/api/billing/apple-transactions").with(loginAs("bill-4")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(transaction("Xcode", "tx-p", mine, "com.other.product"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/billing/apple-transactions").with(loginAs("bill-4")).contentType(MediaType.APPLICATION_JSON)
                        .content(body("not-a-jws")))
                .andExpect(status().isBadRequest());
        assertThat(travelPassRepository.findFirstByUserOrderByExpiresAtDesc(me)).isEmpty();
    }
}
