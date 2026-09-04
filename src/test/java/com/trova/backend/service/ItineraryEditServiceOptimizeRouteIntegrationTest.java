package com.trova.backend.service;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ItineraryEditServiceOptimizeRouteIntegrationTest {

    private static final String PROVIDER_USER_ID = "route-optimize-integration-1";

    @Autowired
    private ItineraryEditService itineraryEditService;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private SavedPlaceRepository savedPlaceRepository;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void tearDown() {
        userRepository.findByProviderAndProviderUserId("google", PROVIDER_USER_ID)
                .ifPresent(user -> {
                    savedPlaceRepository.deleteAll(savedPlaceRepository.findByUserOrderByCreatedAtDescIdDesc(user));
                    processingJobRepository.deleteAll(processingJobRepository.findByUserOrderByCreatedAtDescIdDesc(user));
                    userRepository.delete(user);
                });
    }

    private User newUser() {
        return userRepository.findByProviderAndProviderUserId("google", PROVIDER_USER_ID)
                .orElseGet(() -> userRepository.save(new User("google", PROVIDER_USER_ID, "동선유저", null)));
    }

    @Test
    void 총_이동거리가_최소가_되도록_순서를_재배열하고_저장한다() {
        User user = newUser();
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/optimize1", SourcePlatform.YOUTUBE));
        // 입력 순서: A(37.500) -> B(37.502) -> C(37.501) — 최적 순서는 A,C,B(또는 B,C,A)여야 함
        SavedPlace a = savedPlaceRepository.save(
                new SavedPlace(job, user, "A", "서울", "cafe", 37.500, 127.000, 1, 1));
        SavedPlace b = savedPlaceRepository.save(
                new SavedPlace(job, user, "B", "서울", "cafe", 37.502, 127.000, 1, 2));
        SavedPlace c = savedPlaceRepository.save(
                new SavedPlace(job, user, "C", "서울", "cafe", 37.501, 127.000, 1, 3));

        List<SavedPlace> result = itineraryEditService.optimizeRoute(job.getId(), user, 1).orElseThrow();

        assertThat(result).hasSize(3);
        assertThat(result.get(1).getId()).isEqualTo(c.getId());

        List<SavedPlace> reloaded =
                savedPlaceRepository.findByProcessingJobAndDayNumberOrderByOrderInDayAsc(job, 1);
        assertThat(reloaded).hasSize(3);
        assertThat(reloaded.get(1).getId()).isEqualTo(c.getId());
        assertThat(reloaded.get(0).getOrderInDay()).isEqualTo(1);
        assertThat(reloaded.get(1).getOrderInDay()).isEqualTo(2);
        assertThat(reloaded.get(2).getOrderInDay()).isEqualTo(3);
    }

    @Test
    void 다른_사용자의_job이면_빈_Optional을_반환한다() {
        User owner = newUser();
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(owner, "https://youtu.be/optimize2", SourcePlatform.YOUTUBE));
        savedPlaceRepository.save(new SavedPlace(job, owner, "A", "서울", "cafe", 37.5, 127.0, 1, 1));

        User stranger = userRepository.findByProviderAndProviderUserId("google", "route-optimize-stranger")
                .orElseGet(() -> userRepository.save(new User("google", "route-optimize-stranger", "남", null)));
        try {
            Optional<List<SavedPlace>> result = itineraryEditService.optimizeRoute(job.getId(), stranger, 1);
            assertThat(result).isEmpty();
        } finally {
            userRepository.delete(stranger);
        }
    }
}
