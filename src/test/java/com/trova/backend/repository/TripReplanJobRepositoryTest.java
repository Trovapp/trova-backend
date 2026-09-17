package com.trova.backend.repository;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDate;
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

        List<TripReplanJob> active = tripReplanJobRepository.findByUserAndTripAndIndoorOnlyAndStatusIn(
                user, trip, true, List.of(JobStatus.PENDING, JobStatus.PROCESSING));

        assertThat(active).extracting(TripReplanJob::getId).containsExactly(pending.getId());
    }

    @Test
    void indoorOnly값이_다르면_조회되지_않는다() {
        User user = userRepository.save(new User("google", "2", "테스트유저2", null));
        Trip trip = tripRepository.save(new Trip(user, "테스트 여행2", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)));
        tripReplanJobRepository.save(new TripReplanJob(user, trip, false));

        List<TripReplanJob> active = tripReplanJobRepository.findByUserAndTripAndIndoorOnlyAndStatusIn(
                user, trip, true, List.of(JobStatus.PENDING, JobStatus.PROCESSING));

        assertThat(active).isEmpty();
    }
}
