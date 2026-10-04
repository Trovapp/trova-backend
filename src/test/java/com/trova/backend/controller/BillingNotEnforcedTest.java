package com.trova.backend.controller;

import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.GooglePlacesApiClient;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.service.BillingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 기본 설정(막기 꺼짐, Xcode 거래 안 받음) — 결제가 열리기 전 운영과 같은 상태(#130). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BillingNotEnforcedTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TripDraftRepository tripDraftRepository;

    @MockitoBean
    private GooglePlacesApiClient googlePlacesApiClient;
    @MockitoBean
    private PlaceEmbeddingService placeEmbeddingService;

    @Test
    void 한도를_넘어도_막지_않고_사용량만_보여준다() throws Exception {
        User me = userRepository.save(new User("google", "bill-off-1", "결제유저", null));
        tripDraftRepository.save(new TripDraft(me, List.of(1L), "2박 3일"));

        mockMvc.perform(get("/api/billing/status").with(BillingFlowTest.loginAs("bill-off-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enforced").value(false))
                .andExpect(jsonPath("$.usage.DRAFT.remaining").value(0));
        int code = mockMvc.perform(post("/api/trip-drafts").with(BillingFlowTest.loginAs("bill-off-1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"jobIds\":[1],\"message\":\"2박 3일\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(code).isNotEqualTo(402);
    }

    @Test
    void 기본_설정에서는_테스트_결제를_받지_않는다() throws Exception {
        User me = userRepository.save(new User("google", "bill-off-2", "결제유저", null));
        String jws = BillingFlowTest.transaction("Xcode", "tx-off", BillingService.appAccountToken(me).toString(),
                "com.trovapp.trova.travelpass30");
        mockMvc.perform(post("/api/billing/apple-transactions").with(BillingFlowTest.loginAs("bill-off-2"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"signedTransaction\":\"" + jws + "\"}"))
                .andExpect(status().isNotImplemented());
    }
}
