package com.trova.backend.repository;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class TripReplanJobRepositoryTest {

    @Autowired
    private TripReplanJobRepository tripReplanJobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TripRepository tripRepository;

    @Test
    void PENDING과_PROCESSING만_동일_조건으로_조회한다() {
        User user = userRepository.save(new User("google", "1", "테스트유저", null));
        Trip trip = tripRepository.save(new Trip(user, "테스트 여행", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)));

        TripReplanJob pending = tripReplanJobRepository.save(new TripReplanJob(user, trip, true));
        TripReplanJob done = tripReplanJobRepository.save(new TripReplanJob(user, trip, true));
        done.markProcessing();
        done.markDone("{}");
        tripReplanJobRepository.save(done);

        List<TripReplanJob> active = tripReplanJobRepository.findByUserAndTripAndIndoorOnlyAndStatusInAndUpdatedAtAfter(
                user, trip, true, List.of(JobStatus.PENDING, JobStatus.PROCESSING), LocalDateTime.now().minusMinutes(5));

        assertThat(active).extracting(TripReplanJob::getId).containsExactly(pending.getId());
    }

    @Test
    void indoorOnly값이_다르면_조회되지_않는다() {
        User user = userRepository.save(new User("google", "2", "테스트유저2", null));
        Trip trip = tripRepository.save(new Trip(user, "테스트 여행2", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)));
        tripReplanJobRepository.save(new TripReplanJob(user, trip, false));

        List<TripReplanJob> active = tripReplanJobRepository.findByUserAndTripAndIndoorOnlyAndStatusInAndUpdatedAtAfter(
                user, trip, true, List.of(JobStatus.PENDING, JobStatus.PROCESSING), LocalDateTime.now().minusMinutes(5));

        assertThat(active).isEmpty();
    }

    @Test
    void updatedAt이_기준시각보다_오래된_작업은_조회되지_않는다() {
        User user = userRepository.save(new User("google", "3", "테스트유저3", null));
        Trip trip = tripRepository.save(new Trip(user, "테스트 여행3", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)));
        TripReplanJob stale = tripReplanJobRepository.save(new TripReplanJob(user, trip, true));
        stale.markProcessing();
        tripReplanJobRepository.save(stale);
        // updatedAt을 직접 과거로 되돌려, "배포 중 죽어서 영원히 PROCESSING인 작업"을 흉내낸다.
        ReflectionTestUtils.setField(stale, "updatedAt", LocalDateTime.now().minusMinutes(10));
        tripReplanJobRepository.save(stale);

        List<TripReplanJob> active = tripReplanJobRepository.findByUserAndTripAndIndoorOnlyAndStatusInAndUpdatedAtAfter(
                user, trip, true, List.of(JobStatus.PENDING, JobStatus.PROCESSING), LocalDateTime.now().minusMinutes(5));

        assertThat(active).isEmpty();
    }
}
