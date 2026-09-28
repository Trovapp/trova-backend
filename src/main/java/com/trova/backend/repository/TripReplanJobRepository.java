package com.trova.backend.repository;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface TripReplanJobRepository extends JpaRepository<TripReplanJob, Long> {
    void deleteByUser(User user);

    List<TripReplanJob> findByTrip(Trip trip);

    // 서버 재시작 등으로 대기열에서 사라진 작업 정리용(#43).
    List<TripReplanJob> findByStatusInAndUpdatedAtBefore(List<JobStatus> statuses, LocalDateTime updatedBefore);

    List<TripReplanJob> findByUserAndTripAndIndoorOnlyAndStatusInAndUpdatedAtAfter(
            User user, Trip trip, boolean indoorOnly, List<JobStatus> statuses, LocalDateTime updatedAfter);

    List<TripReplanJob> findByUserAndTripAndIndoorOnlyAndAllPlacesAndStatusInAndUpdatedAtAfter(
            User user, Trip trip, boolean indoorOnly, boolean allPlaces, List<JobStatus> statuses, LocalDateTime updatedAfter);
}
