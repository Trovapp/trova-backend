package com.trova.backend.service;

import com.trova.backend.entity.*;
import com.trova.backend.planner.DraftGenerator;
import com.trova.backend.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** 초안 승인(#106 4일차): 일차·순서·방문 시각·영상 장소 연결을 그대로 옮기고, 두 번 눌러도 여행은 하나만 생긴다. */
@SpringBootTest
class TripDraftApprovalServiceIntegrationTest {

    private static final String OWNER = "trip-draft-approval-1";
    private static final String OTHER = "trip-draft-approval-2";

    @Autowired private TripDraftApprovalService approvalService;
    @Autowired private TripDraftRepository tripDraftRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProcessingJobRepository processingJobRepository;
    @Autowired private SavedPlaceRepository savedPlaceRepository;
    @Autowired private TripRepository tripRepository;
    @Autowired private ItineraryRepository itineraryRepository;
    @Autowired private TripPlaceRepository tripPlaceRepository;

    @AfterEach
    void tearDown() {
        for (String id : List.of(OWNER, OTHER)) {
            userRepository.findByProviderAndProviderUserId("google", id).ifPresent(user -> {
                tripDraftRepository.deleteAll(tripDraftRepository.findAll().stream()
                        .filter(d -> d.getUser().getId().equals(user.getId())).toList());
                tripRepository.findByUserOrderByCreatedAtDesc(user).forEach(trip -> {
                    itineraryRepository.findByTripOrderByDay(trip).forEach(it ->
                            tripPlaceRepository.deleteAll(tripPlaceRepository.findByItineraryOrderByVisitOrder(it)));
                    itineraryRepository.deleteAll(itineraryRepository.findByTripOrderByDay(trip));
                });
                tripRepository.deleteAll(tripRepository.findByUserOrderByCreatedAtDesc(user));
                savedPlaceRepository.deleteAll(savedPlaceRepository.findByUserOrderByCreatedAtDescIdDesc(user));
                processingJobRepository.deleteAll(processingJobRepository.findByUserOrderByCreatedAtDescIdDesc(user));
                userRepository.delete(user);
            });
        }
    }

    private User user(String id) {
        return userRepository.save(new User("google", id, "승인", null));
    }

    private static DraftGenerator.Item item(SavedPlace p, String start, String end) {
        return new DraftGenerator.Item(p.getId(), p.getPlaceName(), p.getCategory(), LocalTime.parse(start),
                LocalTime.parse(end), p.getLatitude(), p.getLongitude());
    }

    /** 1일차: 해수욕장 → 국밥집, 2일차: 시장. 시작일 2026-11-01. */
    private TripDraft readyDraft(User user) {
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/approve-" + System.nanoTime(), SourcePlatform.YOUTUBE));
        SavedPlace beach = savedPlaceRepository.save(new SavedPlace(job, user, "해수욕장", "부산 해운대구", "attraction", 35.15, 129.16));
        SavedPlace food = new SavedPlace(job, user, "국밥집", "부산 수영구", "restaurant", 35.16, 129.11);
        food.applyVideoNotes(List.of("돼지국밥이 진하고 부추를 많이 넣어 먹는다", "오전 11시 전에 가면 줄이 짧다"));
        food = savedPlaceRepository.save(food);
        SavedPlace market = savedPlaceRepository.save(new SavedPlace(job, user, "시장", "부산 중구", "shopping", 35.10, 129.03));
        DraftGenerator.Draft plan = new DraftGenerator.Draft(List.of(
                new DraftGenerator.Day(1, LocalDate.of(2026, 11, 1), List.of(item(beach, "10:00", "11:30"), item(food, "12:00", "13:00"))),
                new DraftGenerator.Day(2, LocalDate.of(2026, 11, 2), List.of(item(market, "10:00", "11:00")))),
                List.of(), List.of(), List.of());
        TripDraft draft = new TripDraft(user, List.of(job.getId()), "11월 1일 부산 1박 2일");
        draft.applyRequest(2, LocalDate.of(2026, 11, 1), "CODE");
        draft.markReady("{}", DraftGenerator.toJson(plan));
        return tripDraftRepository.save(draft);
    }

    @Test
    void 승인하면_초안의_일차_순서_시각을_그대로_여행으로_만든다() {
        User owner = user(OWNER);
        TripDraft draft = readyDraft(owner);

        TripDraftApprovalService.Approval approval = approvalService.approve(owner, draft.getId(), null);

        assertThat(approval.outcome()).isEqualTo(TripDraftApprovalService.Outcome.OK);
        Trip trip = tripRepository.findById(approval.tripId()).orElseThrow();
        assertThat(trip.getTitle()).isEqualTo("부산 1박 2일");
        assertThat(trip.getStartDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(trip.getEndDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        List<Itinerary> days = itineraryRepository.findByTripOrderByDay(trip);
        assertThat(days).extracting(Itinerary::getDate).containsExactly(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2));
        List<TripPlace> day1 = tripPlaceRepository.findByItineraryOrderByVisitOrder(days.get(0));
        assertThat(day1).extracting(TripPlace::getPlaceName).containsExactly("해수욕장", "국밥집");
        assertThat(day1).extracting(TripPlace::getVisitOrder).containsExactly(1, 2);
        assertThat(day1.get(1).getVisitStartTime()).isEqualTo(LocalTime.of(12, 0));
        assertThat(day1.get(0).getSavedPlaceId()).isNotNull();
        assertThat(day1.get(0).getSource()).isEqualTo(PlaceSource.VIDEO);
        // 영상에서 말한 내용이 장소 메모로 옮겨진다(#134). 영상 메모가 없는 장소는 메모가 비어 있다.
        assertThat(day1.get(1).getMemo()).isEqualTo("· 돼지국밥이 진하고 부추를 많이 넣어 먹는다\n· 오전 11시 전에 가면 줄이 짧다");
        assertThat(day1.get(0).getMemo()).isNull();

        TripDraft approved = tripDraftRepository.findById(draft.getId()).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(TripDraftStatus.APPROVED);
        assertThat(approved.getTripId()).isEqualTo(trip.getId());
    }

    @Test
    void 제목을_보내면_그_제목을_쓰고_다시_승인하면_같은_여행을_돌려준다() {
        User owner = user(OWNER);
        TripDraft draft = readyDraft(owner);

        Long first = approvalService.approve(owner, draft.getId(), "  우리 부산 여행 ").tripId();
        Long second = approvalService.approve(owner, draft.getId(), "다른 제목").tripId();

        assertThat(second).isEqualTo(first);
        assertThat(tripRepository.findByUserOrderByCreatedAtDesc(owner)).singleElement()
                .extracting(Trip::getTitle).isEqualTo("우리 부산 여행");
    }

    @Test
    void 동시에_두_번_승인해도_여행은_하나만_생긴다() throws Exception {
        User owner = user(OWNER);
        TripDraft draft = readyDraft(owner);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Long> approve = () -> {
            go.await();
            return approvalService.approve(owner, draft.getId(), null).tripId();
        };
        List<Future<Long>> results = new ArrayList<>(List.of(pool.submit(approve), pool.submit(approve)));
        go.countDown();

        Long a = results.get(0).get();
        Long b = results.get(1).get();
        pool.shutdown();

        assertThat(a).isEqualTo(b);
        assertThat(tripRepository.findByUserOrderByCreatedAtDesc(owner)).hasSize(1);
    }

    @Test
    void 남의_초안은_찾을_수_없고_준비_전_초안은_승인할_수_없다() {
        User owner = user(OWNER);
        User other = user(OTHER);
        TripDraft ready = readyDraft(owner);
        TripDraft waiting = tripDraftRepository.save(new TripDraft(owner, List.of(1L), "부산 1박 2일"));

        assertThat(approvalService.approve(other, ready.getId(), null).outcome())
                .isEqualTo(TripDraftApprovalService.Outcome.NOT_FOUND);
        assertThat(approvalService.approve(owner, waiting.getId(), null).outcome())
                .isEqualTo(TripDraftApprovalService.Outcome.NOT_READY);
        assertThat(tripRepository.findByUserOrderByCreatedAtDesc(owner)).isEmpty();
    }
}
