package com.trova.backend.service;

import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAccountService {

    private final UserRepository userRepository;
    private final SavedPlaceRepository savedPlaceRepository;
    private final ProcessingJobRepository processingJobRepository;

    public UserAccountService(
            UserRepository userRepository,
            SavedPlaceRepository savedPlaceRepository,
            ProcessingJobRepository processingJobRepository
    ) {
        this.userRepository = userRepository;
        this.savedPlaceRepository = savedPlaceRepository;
        this.processingJobRepository = processingJobRepository;
    }

    // DB FK의 ON DELETE 설정과 무관하게 항상 안전하게 지워지도록, 자식(SavedPlace) ->
    // 부모(ProcessingJob) -> User 순서로 앱 레벨에서 명시적으로 삭제한다.
    @Transactional
    public void withdraw(User user) {
        savedPlaceRepository.deleteByUser(user);
        processingJobRepository.deleteByUser(user);
        userRepository.delete(user);
    }
}
