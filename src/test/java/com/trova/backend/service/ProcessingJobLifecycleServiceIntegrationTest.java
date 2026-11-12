package com.trova.backend.service;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.ProcessingStage;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.geocoding.GeocodingResult;
import com.trova.backend.pipeline.ExtractedPlace;
import com.trova.backend.pipeline.ItineraryAssignment;
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
    void updateStage가_현재_단계를_저장한다() {
        ProcessingJob job = newJob();

        lifecycleService.updateStage(job.getId(), ProcessingStage.GEOCODING);

        ProcessingJob reloaded = processingJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getCurrentStage()).isEqualTo(ProcessingStage.GEOCODING);
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
                "관광,명소 > 해수욕장", "http://place.map.kakao.com/1", java.util.List.of());

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
    void savePlace가_영상에서_말한_내용을_함께_저장하고_없으면_빈_목록을_돌려준다() {
        ProcessingJob job = newJob();
        ExtractedPlace withNotes = new ExtractedPlace("크림우동집", "부산", "restaurant", 0.9, null, null,
                List.of("크림우동집"), null, List.of("추천 메뉴: 명란 크림우동", "웨이팅 30분"));
        ExtractedPlace withoutNotes = new ExtractedPlace("해운대", "부산", "attraction", 0.95, null, null, List.of("해운대"));

        lifecycleService.savePlace(job.getId(), withNotes, GeocodingResult.coordinatesOnly(35.16, 129.16));
        lifecycleService.savePlace(job.getId(), withoutNotes, GeocodingResult.coordinatesOnly(35.15, 129.15));

        List<SavedPlace> saved = savedPlaceRepository.findByUserOrderByCreatedAtDescIdDesc(job.getUser());
        SavedPlace udon = saved.stream().filter(p -> p.getPlaceName().equals("크림우동집")).findFirst().orElseThrow();
        SavedPlace beach = saved.stream().filter(p -> p.getPlaceName().equals("해운대")).findFirst().orElseThrow();
        assertThat(udon.getVideoNotes()).containsExactly("추천 메뉴: 명란 크림우동", "웨이팅 30분");
        assertThat(beach.getVideoNotes()).isEmpty();
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

    @Test
    void applyItinerary는_id로_매칭되는_장소들의_day_order를_갱신한다() {
        ProcessingJob job = newJob();
        SavedPlace first = savedPlaceRepository.save(
                new SavedPlace(job, job.getUser(), "첫 장소", "부산", "cafe", 35.1, 129.0));
        SavedPlace second = savedPlaceRepository.save(
                new SavedPlace(job, job.getUser(), "두번째 장소", "부산", "cafe", 35.2, 129.1));

        lifecycleService.applyItinerary(job.getId(), List.of(
                new ItineraryAssignment(first.getId(), 1, 1),
                new ItineraryAssignment(second.getId(), 1, 2)
        ));

        SavedPlace updatedFirst = savedPlaceRepository.findById(first.getId()).orElseThrow();
        SavedPlace updatedSecond = savedPlaceRepository.findById(second.getId()).orElseThrow();
        assertThat(updatedFirst.getDayNumber()).isEqualTo(1);
        assertThat(updatedFirst.getOrderInDay()).isEqualTo(1);
        assertThat(updatedSecond.getOrderInDay()).isEqualTo(2);
    }

    @Test
    void 분석_실패도_뒤쪽_2000자로_잘리고_분석_결과가_DB에_남는다() {
        ProcessingJob job = newJob();
        String message = "머리".repeat(500) + "t".repeat(2500);

        assertThatCode(() -> lifecycleService.markAnalysisFailed(job.getId(), message)).doesNotThrowAnyException();

        ProcessingJob reloaded = processingJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getErrorMessage()).hasSize(2000).isEqualTo(message.substring(message.length() - 2000));
        assertThat(reloaded.getAnalysisStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(reloaded.getAnalysisFinishedAt()).isNotNull();
    }

    @Test
    void 분석_완료는_분석_결과와_시각이_DB에_남는다() {
        ProcessingJob job = newJob();

        lifecycleService.markAnalysisDone(job.getId());

        ProcessingJob reloaded = processingJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getAnalysisStatus()).isEqualTo(JobStatus.DONE);
        assertThat(reloaded.getAnalysisFinishedAt()).isNotNull();
    }

    @Test
    void 중단된_PROCESSING은_재시작_후_영상분석을_다시_시작할_수_있다() {
        ProcessingJob job = newJob();
        lifecycleService.markProcessing(job.getId());
        assertThat(lifecycleService.startAnalysis(job.getId())).isEqualTo(job.getSourceUrl());
        assertThat(processingJobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.PROCESSING);
    }

    @Test
    void 끝난_분석은_일정생성으로_PROCESSING이어도_다시_분석하지_않는다() {
        ProcessingJob job = newJob();
        lifecycleService.markAnalysisDone(job.getId());
        lifecycleService.markProcessing(job.getId());
        assertThat(lifecycleService.startAnalysis(job.getId())).isNull();
        assertThat(processingJobRepository.findById(job.getId()).orElseThrow().getAnalysisStatus()).isEqualTo(JobStatus.DONE);
    }

    @Test
    void 실패나_완료한_작업과_삭제된_작업은_분석을_시작하지_않는다() {
        ProcessingJob job = newJob();
        lifecycleService.markFailed(job.getId(), "실패");
        assertThat(lifecycleService.startAnalysis(job.getId())).isNull();
        lifecycleService.markDone(job.getId());
        assertThat(lifecycleService.startAnalysis(job.getId())).isNull();
        processingJobRepository.deleteById(job.getId());
        assertThat(lifecycleService.startAnalysis(job.getId())).isNull();
    }

    @Test
    void 새_영상분석_시작은_트랜잭션으로_진행상태를_저장한다() {
        ProcessingJob job = newJob();
        assertThat(lifecycleService.startAnalysis(job.getId())).isEqualTo(job.getSourceUrl());
        assertThat(processingJobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.PROCESSING);
    }

    @Test
    void 같은_영상을_다시_분석하면_직접_바로잡은_위치를_이어받는다() {
        // 모모스커피 부산본점을 도모헌점으로 고친 뒤 다시 분석하면 부산본점으로 돌아갔다(수정 확인 QA 2026-11-12, #416).
        ExtractedPlace cafe = new ExtractedPlace("모모스커피", "부산", "cafe", 0.9, null, null, List.of("모모스커피"));
        ExtractedPlace beach = new ExtractedPlace("해운대", "부산", "attraction", 0.9, null, null, List.of("해운대"));
        GeocodingResult mainBranch = new GeocodingResult(
                35.2196, 129.0864, "모모스커피 부산본점", null, "부산 금정구", null, "음식점 > 카페", null, List.of());
        GeocodingResult beachSpot = GeocodingResult.coordinatesOnly(35.1587, 129.1604);

        ProcessingJob first = newJob();
        lifecycleService.startAnalysis(first.getId());
        lifecycleService.completeAnalysis(first.getId(), List.of(cafe, beach), List.of(mainBranch, beachSpot));
        SavedPlace fixed = savedPlaceRepository.findByProcessingJob(first).stream()
                .filter(p -> p.getPlaceName().equals("모모스커피 부산본점")).findFirst().orElseThrow();
        fixed.relocateTo("모모스커피 도모헌점", 35.1400, 129.1100, "부산 수영구", null, "gp-domoheon");
        savedPlaceRepository.save(fixed);

        ProcessingJob again = processingJobRepository.save(
                new ProcessingJob(first.getUser(), "https://youtu.be/lifecycle?si=again", SourcePlatform.YOUTUBE));
        lifecycleService.startAnalysis(again.getId());
        lifecycleService.completeAnalysis(again.getId(), List.of(cafe, beach), List.of(mainBranch, beachSpot));

        List<SavedPlace> result = savedPlaceRepository.findByProcessingJob(again);
        SavedPlace inherited = result.stream().filter(p -> p.getPlaceName().startsWith("모모스")).findFirst().orElseThrow();
        assertThat(inherited.getPlaceName()).isEqualTo("모모스커피 도모헌점");
        assertThat(inherited.getLatitude()).isEqualTo(35.1400);
        assertThat(inherited.getGooglePlaceId()).isEqualTo("gp-domoheon");
        assertThat(inherited.getRelocatedFromName()).isEqualTo("모모스커피 부산본점");
        SavedPlace untouched = result.stream().filter(p -> p.getPlaceName().equals("해운대")).findFirst().orElseThrow();
        assertThat(untouched.getLatitude()).isEqualTo(35.1587);
        assertThat(untouched.getRelocatedFromName()).isNull();
    }
}
