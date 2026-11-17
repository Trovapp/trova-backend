package com.trova.backend.controller;

import com.trova.backend.entity.ConsentType;
import com.trova.backend.entity.User;
import com.trova.backend.entity.UserConsent;
import com.trova.backend.repository.UserConsentRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtAuthenticationToken;
import com.trova.backend.service.ConsentService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:user-consent-controller;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "app.consent.terms-version=2026-11-17",
        "app.consent.privacy-version=2026-11-16"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Transactional
class ConsentControllerTest {

    private static final String TERMS_VERSION = "2026-11-17";
    private static final String PRIVACY_VERSION = "2026-11-16";
    private static final String VALID_REQUEST = """
            {"termsVersion":"2026-11-17","privacyVersion":"2026-11-16","ageOver14":true}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private UserConsentRepository consentRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User user;

    @BeforeEach
    void 사용자를_준비한다() {
        user = userRepository.save(new User("google", "consent-user", "동의 사용자", null));
    }

    @AfterEach
    void 트랜잭션_밖에서_저장한_사용자를_삭제한다() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                consentRepository.deleteByUser(user);
                userRepository.delete(user);
            });
        }
    }

    private RequestPostProcessor login() {
        return authentication(new JwtAuthenticationToken(user.getId()));
    }

    @Test
    void 로그인하지_않으면_조회와_저장은_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/me/consents"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/me/consents").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 처음에는_동의가_필요하고_설정한_현재_버전을_반환한다() throws Exception {
        mockMvc.perform(get("/api/me/consents").with(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needed").value(true))
                .andExpect(jsonPath("$.termsVersion").value(TERMS_VERSION))
                .andExpect(jsonPath("$.privacyVersion").value(PRIVACY_VERSION));
    }

    @Test
    void 올바른_동의를_저장하면_204이고_더는_동의가_필요하지_않다() throws Exception {
        mockMvc.perform(post("/api/me/consents").with(login())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/me/consents").with(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needed").value(false));
        List<UserConsent> consents = consentRepository.findByUser(user);
        assertThat(consents).hasSize(3);
        assertThat(consents).extracting(UserConsent::getType)
                .containsExactlyInAnyOrder(ConsentType.TERMS, ConsentType.PRIVACY, ConsentType.AGE_OVER_14);
        assertThat(consents).allSatisfy(consent -> assertThat(consent.getAgreedAt()).isNotNull());
        assertThat(consentRepository.existsByUserAndTypeAndVersion(user, ConsentType.TERMS, TERMS_VERSION)).isTrue();
        assertThat(consentRepository.existsByUserAndTypeAndVersion(user, ConsentType.PRIVACY, PRIVACY_VERSION)).isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 트랜잭션_밖에서도_세_동의가_저장되고_더는_동의가_필요하지_않다() throws Exception {
        mockMvc.perform(post("/api/me/consents").with(login())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isNoContent());

        assertThat(consentRepository.findByUser(user)).extracting(UserConsent::getType)
                .containsExactlyInAnyOrder(ConsentType.TERMS, ConsentType.PRIVACY, ConsentType.AGE_OVER_14);
        mockMvc.perform(get("/api/me/consents").with(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needed").value(false));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 두_번째_동의_저장이_실패하면_첫_동의도_롤백한다() throws Exception {
        doAnswer(invocation -> {
            assertThat(consentRepository.existsByUserAndTypeAndVersion(user, ConsentType.TERMS, TERMS_VERSION)).isTrue();
            throw new IllegalStateException("동의 저장 실패");
        }).when(consentRepository).insertIfAbsent(eq(user.getId()), eq(ConsentType.PRIVACY.name()),
                eq(PRIVACY_VERSION), any(LocalDateTime.class));

        assertThatThrownBy(() -> mockMvc.perform(post("/api/me/consents").with(login())
                .contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST)))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("동의 저장 실패");

        assertThat(consentRepository.findByUser(user)).isEmpty();
        mockMvc.perform(get("/api/me/consents").with(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needed").value(true));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 같은_동의를_동시에_제출해도_모두_204이고_세_행만_저장한다() throws Exception {
        CountDownLatch checked = new CountDownLatch(2);
        // 두 요청 모두 동의가 없다고 조회한 뒤 삽입하는 경합을 재현한다.
        doAnswer(invocation -> {
            checked.countDown();
            assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue();
            return false;
        }).when(consentRepository).existsByUserAndTypeAndVersion(any(User.class), eq(ConsentType.TERMS), eq(TERMS_VERSION));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                mockMvc.perform(post("/api/me/consents").with(login())
                                .contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                        .andExpect(status().isNoContent());
                return null;
            });
            Future<?> second = executor.submit(() -> {
                mockMvc.perform(post("/api/me/consents").with(login())
                                .contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                        .andExpect(status().isNoContent());
                return null;
            });
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);

            assertThat(consentRepository.findByUser(user)).extracting(UserConsent::getType)
                    .containsExactlyInAnyOrder(ConsentType.TERMS, ConsentType.PRIVACY, ConsentType.AGE_OVER_14);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void 약관이나_개인정보_버전이_다르면_400이고_일부도_저장하지_않는다() throws Exception {
        for (String body : List.of(
                VALID_REQUEST.replace(TERMS_VERSION, "2026-10-14"),
                VALID_REQUEST.replace(PRIVACY_VERSION, "2026-10-14"))) {
            mockMvc.perform(post("/api/me/consents").with(login())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
            assertThat(consentRepository.findByUser(user)).isEmpty();
        }
    }

    @Test
    void 만14세_확인이_false나_null이거나_빠지면_400이다() throws Exception {
        for (String body : List.of(
                VALID_REQUEST.replace("true", "false"),
                VALID_REQUEST.replace("true", "null"),
                VALID_REQUEST.replace(",\"ageOver14\":true", ""))) {
            mockMvc.perform(post("/api/me/consents").with(login())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
            assertThat(consentRepository.findByUser(user)).isEmpty();
        }
    }

    @Test
    void 버전이_빠지거나_null이면_400이다() throws Exception {
        for (String body : List.of(
                "{\"ageOver14\":true}",
                "{\"termsVersion\":null,\"privacyVersion\":null,\"ageOver14\":true}")) {
            mockMvc.perform(post("/api/me/consents").with(login())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
            assertThat(consentRepository.findByUser(user)).isEmpty();
        }
    }

    @Test
    void 같은_버전을_다시_보내도_행과_최초_동의시각은_그대로다() throws Exception {
        mockMvc.perform(post("/api/me/consents").with(login())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();
        List<UserConsent> original = consentRepository.findByUser(user);

        mockMvc.perform(post("/api/me/consents").with(login())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();
        assertThat(consentRepository.findByUser(user))
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("user")
                .containsExactlyInAnyOrderElementsOf(original);
    }

    @Test
    void 약관_버전만_올라도_다시_동의가_필요하다() throws Exception {
        new ConsentService(consentRepository, "2026-10-14", PRIVACY_VERSION)
                .record(user, "2026-10-14", PRIVACY_VERSION, true);
        mockMvc.perform(get("/api/me/consents").with(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needed").value(true));
        mockMvc.perform(post("/api/me/consents").with(login())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isNoContent());
        assertThat(consentRepository.findByUser(user)).hasSize(4);
        mockMvc.perform(get("/api/me/consents").with(login()))
                .andExpect(jsonPath("$.needed").value(false));
    }

    @Test
    void 개인정보_버전만_올라도_다시_동의가_필요하다() throws Exception {
        new ConsentService(consentRepository, TERMS_VERSION, "2026-10-14")
                .record(user, TERMS_VERSION, "2026-10-14", true);
        mockMvc.perform(get("/api/me/consents").with(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needed").value(true));
    }

    @Test
    void 현재_약관과_개인정보에_동의해도_연령_확인이_없으면_동의가_필요하다() throws Exception {
        consentRepository.save(new UserConsent(user, ConsentType.TERMS, TERMS_VERSION));
        consentRepository.save(new UserConsent(user, ConsentType.PRIVACY, PRIVACY_VERSION));
        mockMvc.perform(get("/api/me/consents").with(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needed").value(true));
    }

    @Test
    void 동의가_없어도_다른_API는_막지_않는다() throws Exception {
        mockMvc.perform(get("/api/users/me").with(login()))
                .andExpect(status().isOk());
    }

    @Test
    void 다른_사용자의_동의는_본인의_동의를_대신하지_않는다() throws Exception {
        User other = userRepository.save(new User("google", "consent-other", "다른 사용자", null));
        new ConsentService(consentRepository, TERMS_VERSION, PRIVACY_VERSION)
                .record(other, TERMS_VERSION, PRIVACY_VERSION, true);
        mockMvc.perform(get("/api/me/consents").with(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needed").value(true));
    }

    @Test
    void 탈퇴하면_모든_버전의_동의가_삭제되고_다른_사용자_동의는_남는다() throws Exception {
        ConsentService service = new ConsentService(consentRepository, TERMS_VERSION, PRIVACY_VERSION);
        service.record(user, TERMS_VERSION, PRIVACY_VERSION, true);
        consentRepository.save(new UserConsent(user, ConsentType.TERMS, "2026-10-14"));
        User other = userRepository.save(new User("google", "consent-other", "다른 사용자", null));
        service.record(other, TERMS_VERSION, PRIVACY_VERSION, true);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(delete("/api/users/me").with(login()))
                .andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();

        assertThat(userRepository.findById(user.getId())).isEmpty();
        assertThat(consentRepository.findByUser(user)).isEmpty();
        assertThat(consentRepository.findByUser(other)).hasSize(3);
        assertThat(userRepository.findById(other.getId())).isPresent();
    }
}
