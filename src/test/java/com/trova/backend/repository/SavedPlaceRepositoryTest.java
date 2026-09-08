package com.trova.backend.repository;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class SavedPlaceRepositoryTest {

    @Autowired
    private SavedPlaceRepository savedPlaceRepository;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void 본인_소유_장소만_id로_조회된다() {
        User owner = userRepository.save(new User("google", "3", "주인", null));
        User other = userRepository.save(new User("google", "4", "남", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(owner, "https://youtu.be/e", SourcePlatform.YOUTUBE));
        SavedPlace place = savedPlaceRepository.save(
                new SavedPlace(job, owner, "해운대", "부산", "attraction", 35.16, 129.16));

        Optional<SavedPlace> byOwner = savedPlaceRepository.findByIdAndUser(place.getId(), owner);
        Optional<SavedPlace> byOther = savedPlaceRepository.findByIdAndUser(place.getId(), other);

        assertThat(byOwner).isPresent();
        assertThat(byOther).isEmpty();
    }

    @Test
    void 사용자의_장소를_최신순으로_조회한다() {
        User user = userRepository.save(new User("google", "5", "유저", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/f", SourcePlatform.YOUTUBE));
        SavedPlace first = savedPlaceRepository.save(
                new SavedPlace(job, user, "장소1", null, "cafe", null, null));
        SavedPlace second = savedPlaceRepository.save(
                new SavedPlace(job, user, "장소2", null, "cafe", null, null));

        List<SavedPlace> places = savedPlaceRepository.findByUserOrderByCreatedAtDescIdDesc(user);

        assertThat(places).extracting(SavedPlace::getId)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    void dayNumber와_orderInDay가_저장되고_조회된다() {
        User user = userRepository.save(new User("google", "6", "일정유저", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/itinerary", SourcePlatform.YOUTUBE));
        SavedPlace place = savedPlaceRepository.save(
                new SavedPlace(job, user, "해운대", "부산", "attraction", 35.16, 129.16, 2, 3));

        SavedPlace reloaded = savedPlaceRepository.findById(place.getId()).orElseThrow();

        assertThat(reloaded.getDayNumber()).isEqualTo(2);
        assertThat(reloaded.getOrderInDay()).isEqualTo(3);
    }

    @Test
    void job의_title이_SavedPlace_생성시_복사되어_저장된다() {
        User user = userRepository.save(new User("google", "8", "제목유저", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/title", SourcePlatform.YOUTUBE));
        job.setTitle("부산 여행 브이로그");
        SavedPlace place = savedPlaceRepository.save(
                new SavedPlace(job, user, "해운대", "부산", "attraction", 35.16, 129.16));

        SavedPlace reloaded = savedPlaceRepository.findById(place.getId()).orElseThrow();

        assertThat(reloaded.getTitle()).isEqualTo("부산 여행 브이로그");
    }

    @Test
    void 일정형이_아니면_dayNumber와_orderInDay가_null이다() {
        User user = userRepository.save(new User("google", "7", "일반유저", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/normal", SourcePlatform.YOUTUBE));
        SavedPlace place = savedPlaceRepository.save(
                new SavedPlace(job, user, "장소", null, "cafe", null, null));

        SavedPlace reloaded = savedPlaceRepository.findById(place.getId()).orElseThrow();

        assertThat(reloaded.getDayNumber()).isNull();
        assertThat(reloaded.getOrderInDay()).isNull();
    }
}
