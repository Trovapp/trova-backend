package com.trova.backend.service;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.geocoding.GeocodingResult;
import com.trova.backend.pipeline.ExtractedPlace;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 트랜잭션 경계를 실제로 검증해야 하므로 일부러 클래스 레벨 {@code @Transactional}을 붙이지 않는다.
 * 각 lifecycle 메서드가 자기 트랜잭션으로 커밋되는지, 그리고 error_message 길이 처리(varchar(2000) +
 * 꼬리 잘라내기)가 실제 DB에 반영되는지 확인한다.
 */
@SpringBootTest
class ProcessingJobLifecycleServiceIntegrationTest {

    private static final String PROVIDER_USER_ID = "lifecycle-integration-1";

    @Autowired
    private ProcessingJobLifecycleService lifecycleService;

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

    private ProcessingJob newJob() {
        User user = userRepository.findByProviderAndProviderUserId("google", PROVIDER_USER_ID)
                .orElseGet(() -> userRepository.save(new User("google", PROVIDER_USER_ID, "라이프사이클", null)));
        return processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/lifecycle", SourcePlatform.YOUTUBE));
    }

    @Test
    void markProcessing_직후_다른_조회에서_PROCESSING이_보인다() {
        ProcessingJob job = newJob();

        String sourceUrl = lifecycleService.markProcessing(job.getId());

        // markProcessing()의 트랜잭션이 이미 커밋됐으므로, 새 영속성 컨텍스트로 읽어도 PROCESSING이어야 한다.
        ProcessingJob reloaded = processingJobRepository.findById(job.getId()).orElseThrow();
        assertThat(sourceUrl).isEqualTo("https://youtu.be/lifecycle");
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.PROCESSING);
    }

    @Test
    void 옛_255자_제한을_넘는_에러메시지도_그대로_저장된다() {
        ProcessingJob job = newJob();
        String message = "y".repeat(1000);

        assertThatCode(() -> lifecycleService.markFailed(job.getId(), message)).doesNotThrowAnyException();

        ProcessingJob reloaded = processingJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(reloaded.getErrorMessage()).hasSize(1000).isEqualTo(message);
    }

    @Test
    void 최대길이를_넘는_에러메시지는_뒤쪽_2000자로_잘려_저장된다() {
        ProcessingJob job = newJob();
        String head = "머리".repeat(500);          // 1000자
        String tail = "t".repeat(2500);
        String message = head + tail;             // 3500자

        assertThatCode(() -> lifecycleService.markFailed(job.getId(), message)).doesNotThrowAnyException();

        ProcessingJob reloaded = processingJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(reloaded.getErrorMessage())
                .hasSize(2000)
                .isEqualTo(message.substring(message.length() - 2000));
    }

    @Test
    void null_에러메시지도_예외없이_저장된다() {
        ProcessingJob job = newJob();

        assertThatCode(() -> lifecycleService.markFailed(job.getId(), null)).doesNotThrowAnyException();

        ProcessingJob reloaded = processingJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(reloaded.getErrorMessage()).isNull();
    }

    @Test
    void setTitle이_영상_제목을_저장한다() {
        ProcessingJob job = newJob();

        lifecycleService.setTitle(job.getId(), "부산 여행 브이로그");

        ProcessingJob reloaded = processingJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("부산 여행 브이로그");
    }

    @Test
    void savePlace가_dayNumber와_orderInDay를_함께_저장한다() {
        ProcessingJob job = newJob();
        ExtractedPlace extracted =
                new ExtractedPlace("해운대", "부산", "attraction", 0.95, 2, 3, List.of("해운대"));
        GeocodingResult geocoded = new GeocodingResult(
                35.16, 129.16, "해운대해수욕장", "051-123-4567", "부산 해운대구 우동", "부산 해운대구 해운대해변로",
                "관광,명소 > 해수욕장", "http://place.map.kakao.com/1");

        lifecycleService.savePlace(job.getId(), extracted, geocoded);

        SavedPlace saved = savedPlaceRepository.findByUserOrderByCreatedAtDescIdDesc(job.getUser()).get(0);
        assertThat(saved.getDayNumber()).isEqualTo(2);
        assertThat(saved.getOrderInDay()).isEqualTo(3);
        assertThat(saved.getPlaceName()).isEqualTo("해운대해수욕장");
        assertThat(saved.getPhone()).isEqualTo("051-123-4567");
        assertThat(saved.getAddress()).isEqualTo("부산 해운대구 우동");
        assertThat(saved.getRoadAddress()).isEqualTo("부산 해운대구 해운대해변로");
        assertThat(saved.getKakaoCategoryName()).isEqualTo("관광,명소 > 해수욕장");
        assertThat(saved.getKakaoPlaceUrl()).isEqualTo("http://place.map.kakao.com/1");
    }

    @Test
    void savePlace가_matchedName이_없으면_추출된_이름을_그대로_저장한다() {
        ProcessingJob job = newJob();
        ExtractedPlace extracted =
                new ExtractedPlace("광알리", "부산", "attraction", 0.6, null, null, List.of("광알리"));
        GeocodingResult geocoded = GeocodingResult.coordinatesOnly(35.17, 129.07);

        lifecycleService.savePlace(job.getId(), extracted, geocoded);

        SavedPlace saved = savedPlaceRepository.findByUserOrderByCreatedAtDescIdDesc(job.getUser()).get(0);
        assertThat(saved.getPlaceName()).isEqualTo("광알리");
        assertThat(saved.getPhone()).isNull();
        assertThat(saved.getAddress()).isNull();
        assertThat(saved.getKakaoPlaceUrl()).isNull();
    }
}
