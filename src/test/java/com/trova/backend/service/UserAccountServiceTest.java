package com.trova.backend.service;

import com.trova.backend.entity.*;
import com.trova.backend.recommendation.GooglePlacesApiClient;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class UserAccountServiceTest {

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private SavedPlaceRepository savedPlaceRepository;

    @Autowired
    private TripService tripService;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private BookmarkRepository bookmarkRepository;

    @Autowired
    private BookmarkFolderRepository bookmarkFolderRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private ItineraryRepository itineraryRepository;

    @Autowired
    private TripPlaceRepository tripPlaceRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private TripReplanJobRepository tripReplanJobRepository;

    @Autowired
    private UserPreferenceRepository userPreferenceRepository;

    @Autowired
    private UserPreferenceSignalRepository userPreferenceSignalRepository;

    @Autowired
    private TripDraftRepository tripDraftRepository;

    @Autowired
    private TravelPassRepository travelPassRepository;

    @Autowired
    private RetainedPurchaseRepository retainedPurchaseRepository;

    @Autowired
    private PurchaseRetentionService purchaseRetentionService;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private GooglePlacesApiClient googlePlacesApiClient;

    @MockitoBean
    private PlaceEmbeddingService placeEmbeddingService;

    @Test
    void 탈퇴하면_본인의_장소와_작업과_계정이_전부_삭제된다() {
        User me = userRepository.save(new User("google", "withdraw-1", "탈퇴할유저", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/w1", SourcePlatform.YOUTUBE));
        SavedPlace place = savedPlaceRepository.save(new SavedPlace(job, me, "지워질 장소", null, "cafe", null, null));
        Long userId = me.getId();
        Long jobId = job.getId();
        Long placeId = place.getId();

        userAccountService.withdraw(me);

        assertThat(userRepository.findById(userId)).isEmpty();
        assertThat(processingJobRepository.findById(jobId)).isEmpty();
        assertThat(savedPlaceRepository.findById(placeId)).isEmpty();
    }

    @Test
    void 탈퇴는_다른_유저의_데이터를_건드리지_않는다() {
        User me = userRepository.save(new User("google", "withdraw-2", "탈퇴할유저2", null));
        User other = userRepository.save(new User("google", "withdraw-other", "남은유저", null));
        ProcessingJob otherJob = processingJobRepository.save(new ProcessingJob(other, "https://youtu.be/w2", SourcePlatform.YOUTUBE));
        SavedPlace otherPlace = savedPlaceRepository.save(new SavedPlace(otherJob, other, "남의 장소", null, "cafe", null, null));

        userAccountService.withdraw(me);

        assertThat(userRepository.findById(other.getId())).isPresent();
        assertThat(processingJobRepository.findById(otherJob.getId())).isPresent();
        assertThat(savedPlaceRepository.findById(otherPlace.getId())).isPresent();
    }

    @Test
    void 찜_폴더_여행_알림_재구성기록_선호데이터가_있어도_탈퇴되고_모두_삭제된다() {
        User me = userRepository.save(new User("google", "withdraw-3", "데이터많은유저", null));
        Place place = placeRepository.save(new Place("withdraw-place-1", "돈사돈", null, null, null, null, 33.4, 126.5, null));

        BookmarkFolder folder = bookmarkFolderRepository.save(new BookmarkFolder(me, "맛집", "#FF6B4A"));
        Bookmark bookmark = new Bookmark(me, place);
        bookmark.applyFolder(folder);
        bookmark = bookmarkRepository.save(bookmark);

        Trip trip = tripService.createTrip(me, "제주 여행", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1));
        TripPlace tripPlace = tripService.addPlaceToDay(me, trip.getId(), 1, "withdraw-place-1").orElseThrow();
        Itinerary itinerary = itineraryRepository.findByTripOrderByDay(trip).get(0);
        Notification notification = notificationRepository.save(
                new Notification(me, itinerary, "비 소식", "우산 챙기세요", 0.8, tripPlace.getId()));
        TripReplanJob replanJob = tripReplanJobRepository.save(new TripReplanJob(me, trip, false));
        UserPreference preference = userPreferenceRepository.save(new UserPreference(me, "calm", 0.5));
        UserPreferenceSignal signal = userPreferenceSignalRepository.save(
                new UserPreferenceSignal(me, place, SignalType.BOOKMARK));
        // 실제 요청처럼 새 영속성 컨텍스트에서 탈퇴하도록 준비 데이터를 DB에 반영하고 비운다.
        entityManager.flush();
        entityManager.clear();
        me = userRepository.findById(me.getId()).orElseThrow();

        userAccountService.withdraw(me);
        // @Transactional 테스트는 커밋 없이 롤백되므로, FK 제약 위반이 실제로 드러나도록 여기서 flush한다.
        entityManager.flush();
        entityManager.clear();

        assertThat(userRepository.findById(me.getId())).isEmpty();
        assertThat(bookmarkRepository.findById(bookmark.getId())).isEmpty();
        assertThat(bookmarkFolderRepository.findById(folder.getId())).isEmpty();
        assertThat(tripRepository.findById(trip.getId())).isEmpty();
        assertThat(itineraryRepository.findById(itinerary.getId())).isEmpty();
        assertThat(tripPlaceRepository.findById(tripPlace.getId())).isEmpty();
        assertThat(notificationRepository.findById(notification.getId())).isEmpty();
        assertThat(tripReplanJobRepository.findById(replanJob.getId())).isEmpty();
        assertThat(userPreferenceRepository.findById(preference.getId())).isEmpty();
        assertThat(userPreferenceSignalRepository.findById(signal.getId())).isEmpty();
        // 여러 회원이 함께 쓰는 장소 마스터 데이터는 지우지 않는다.
        assertThat(placeRepository.findById(place.getId())).isPresent();
    }

    @Test
    void 일정_초안이_있어도_탈퇴되고_초안도_삭제된다() {
        // #119: trip_drafts가 회원을 외래키로 참조하는데 삭제 목록에 없어 탈퇴가 실패했다.
        User me = userRepository.save(new User("google", "withdraw-draft", "초안있는유저", null));
        TripDraft draft = tripDraftRepository.save(new TripDraft(me, java.util.List.of(1L), "2박 3일"));
        Long userId = me.getId();
        Long draftId = draft.getId();

        userAccountService.withdraw(me);
        entityManager.flush();

        assertThat(userRepository.findById(userId)).isEmpty();
        assertThat(tripDraftRepository.findById(draftId)).isEmpty();
    }

    @Test
    void 탈퇴하면_구매_기록은_회원과_끊어_5년_분리_보관하고_기간이_지나면_파기한다() {
        // 개인정보처리방침: 구매 기록은 전자상거래법상 5년 보관(탈퇴해도 분리 보관), 그 밖의 회원 데이터는 지체 없이 파기.
        User me = userRepository.save(new User("google", "withdraw-pass", "패스유저", null));
        LocalDateTime bought = LocalDateTime.of(2026, 10, 5, 12, 0);
        travelPassRepository.save(new TravelPass(me, "tx-retain-1", "com.trovapp.trova.travelpass30", "Xcode",
                bought, bought.plusDays(30), bought));
        Long userId = me.getId();

        userAccountService.withdraw(me);
        entityManager.flush();

        assertThat(userRepository.findById(userId)).isEmpty();
        assertThat(travelPassRepository.findByTransactionId("tx-retain-1")).isEmpty();
        RetainedPurchase kept = retainedPurchaseRepository.findByTransactionId("tx-retain-1").orElseThrow();
        assertThat(kept.getRetainUntil()).isEqualTo(bought.plusYears(5));
        assertThat(kept.getPurchasedAt()).isEqualTo(bought);

        // 5년이 지나면 지운다 — 보관 기간이 끝난 기록 하나와 아직 남은 기록 하나로 확인.
        retainedPurchaseRepository.save(new RetainedPurchase(
                new TravelPass(me, "tx-old", "com.trovapp.trova.travelpass30", "Xcode",
                        bought.minusYears(6), bought.minusYears(6).plusDays(30), bought.minusYears(6)),
                bought, bought.minusYears(1)));
        purchaseRetentionService.purgeExpired();
        assertThat(retainedPurchaseRepository.findByTransactionId("tx-old")).isEmpty();
        assertThat(retainedPurchaseRepository.findByTransactionId("tx-retain-1")).isPresent();
    }
}
