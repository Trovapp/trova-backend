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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 영속성 컨텍스트를 사용하는 통합 테스트 — 일부러 클래스 레벨 {@code @Transactional}을 붙이지
 * 않아 운영과 동일한 조건(open-in-view=false, 컨트롤러의 findByIdAndUser 호출이 자기 트랜잭션에서
 * 끝난 뒤 서비스가 새 트랜잭션을 여는 상황)을 재현한다. moveToDay/reorder가 엔티티를 서비스
 * 트랜잭션 "안에서" 다시 조회하지 않으면(준영속 엔티티를 그대로 mutate하면) 이 테스트는 실패한다.
 */
@SpringBootTest
class ItineraryEditServiceIntegrationTest {

    private static final String PROVIDER_USER_ID = "itinerary-edit-integration-1";

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
                .orElseGet(() -> userRepository.save(new User("google", PROVIDER_USER_ID, "편집유저", null)));
    }

    @Test
    void moveToDay는_별도_트랜잭션에서_조회한_장소_변경을_실제로_DB에_반영한다() {
        User user = newUser();
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/edit1", SourcePlatform.YOUTUBE));
        SavedPlace place = savedPlaceRepository.save(
                new SavedPlace(job, user, "장소", "부산", "cafe", 35.1, 129.0, 2, 1));

        itineraryEditService.moveToDay(place.getId(), user, 1);

        SavedPlace reloaded = savedPlaceRepository.findById(place.getId()).orElseThrow();
        assertThat(reloaded.getDayNumber()).isEqualTo(1);
        assertThat(reloaded.getOrderInDay()).isEqualTo(1);
    }

    @Test
    void reorder는_두_장소의_순서를_모두_DB에_반영한다() {
        User user = newUser();
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/edit2", SourcePlatform.YOUTUBE));
        SavedPlace first = savedPlaceRepository.save(
                new SavedPlace(job, user, "첫번째", "부산", "cafe", 35.1, 129.0, 1, 1));
        SavedPlace second = savedPlaceRepository.save(
                new SavedPlace(job, user, "두번째", "부산", "cafe", 35.2, 129.1, 1, 2));

        itineraryEditService.reorder(first.getId(), user, "DOWN");

        SavedPlace reloadedFirst = savedPlaceRepository.findById(first.getId()).orElseThrow();
        SavedPlace reloadedSecond = savedPlaceRepository.findById(second.getId()).orElseThrow();
        assertThat(reloadedFirst.getOrderInDay()).isEqualTo(2);
        assertThat(reloadedSecond.getOrderInDay()).isEqualTo(1);
    }
}
