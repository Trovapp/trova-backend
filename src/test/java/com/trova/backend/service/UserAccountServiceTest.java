package com.trova.backend.service;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

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
}
