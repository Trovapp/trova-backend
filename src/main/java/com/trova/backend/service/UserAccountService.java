package com.trova.backend.service;

import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import com.trova.backend.repository.BookmarkFolderRepository;
import com.trova.backend.repository.BookmarkRepository;
import com.trova.backend.repository.NotificationRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripReplanJobRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserPreferenceRepository;
import com.trova.backend.repository.UserPreferenceSignalRepository;
import com.trova.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAccountService {

    private final UserRepository userRepository;
    private final SavedPlaceRepository savedPlaceRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final TripRepository tripRepository;
    private final TripService tripService;
    private final TripReplanJobRepository tripReplanJobRepository;
    private final NotificationRepository notificationRepository;
    private final BookmarkRepository bookmarkRepository;
    private final BookmarkFolderRepository bookmarkFolderRepository;
    private final UserPreferenceRepository userPreferenceRepository;
    private final UserPreferenceSignalRepository userPreferenceSignalRepository;

    public UserAccountService(
            UserRepository userRepository,
            SavedPlaceRepository savedPlaceRepository,
            ProcessingJobRepository processingJobRepository,
            TripRepository tripRepository,
            TripService tripService,
            TripReplanJobRepository tripReplanJobRepository,
            NotificationRepository notificationRepository,
            BookmarkRepository bookmarkRepository,
            BookmarkFolderRepository bookmarkFolderRepository,
            UserPreferenceRepository userPreferenceRepository,
            UserPreferenceSignalRepository userPreferenceSignalRepository
    ) {
        this.userRepository = userRepository;
        this.savedPlaceRepository = savedPlaceRepository;
        this.processingJobRepository = processingJobRepository;
        this.tripRepository = tripRepository;
        this.tripService = tripService;
        this.tripReplanJobRepository = tripReplanJobRepository;
        this.notificationRepository = notificationRepository;
        this.bookmarkRepository = bookmarkRepository;
        this.bookmarkFolderRepository = bookmarkFolderRepository;
        this.userPreferenceRepository = userPreferenceRepository;
        this.userPreferenceSignalRepository = userPreferenceSignalRepository;
    }

    // 회원을 참조하는 데이터를 전부 지운 뒤 회원을 지운다(탈퇴 시 지체 없이 파기).
    // 예전엔 SavedPlace·ProcessingJob만 지워서, 찜·여행 등이 있는 회원은 users 삭제가
    // FK 제약 위반으로 실패했다(#15). DB FK의 ON DELETE 설정과 무관하게 항상 안전하도록
    // 참조하는 쪽 -> 참조되는 쪽 순서로 앱 레벨에서 명시적으로 삭제한다.
    // 여러 회원이 함께 쓰는 장소 마스터(Place)는 지우지 않는다.
    @Transactional
    public void withdraw(User user) {
        // 알림은 일정(Itinerary)을, 재구성 기록은 여행을 참조하므로 여행보다 먼저 지운다.
        notificationRepository.deleteByUser(user);
        tripReplanJobRepository.deleteByUser(user);
        // 여행 -> 일정 -> 일정 속 장소 삭제는 여행 삭제와 같은 경로를 재사용한다.
        for (Trip trip : tripRepository.findByUserOrderByCreatedAtDesc(user)) {
            tripService.deleteTrip(user, trip.getId());
        }
        // 찜은 폴더를 참조하므로 폴더보다 먼저 지운다.
        bookmarkRepository.deleteByUser(user);
        bookmarkFolderRepository.deleteByUser(user);
        userPreferenceSignalRepository.deleteByUser(user);
        userPreferenceRepository.deleteByUser(user);
        savedPlaceRepository.deleteByUser(user);
        processingJobRepository.deleteByUser(user);
        userRepository.delete(user);
    }
}
