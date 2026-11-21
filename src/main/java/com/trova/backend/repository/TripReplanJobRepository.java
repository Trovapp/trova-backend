package com.trova.backend.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TripReplanJobRepository extends JpaRepository<TripReplanJob, Long> {
    @EntityGraph(attributePaths = {"user", "trip.user", "trip.members.user"})
    Optional<TripReplanJob> findById(Long id);

    void deleteByUser(User user);

    List<TripReplanJob> findByTrip(Trip trip);

    // 서버 재시작 등으로 대기열에서 사라진 작업 정리용(#43).
    List<TripReplanJob> findByStatusInAndUpdatedAtBefore(List<JobStatus> statuses, LocalDateTime updatedBefore);

    // 멈춘 재구성 다시 보내기용(ADR 0043).
    @Query("select j.id from TripReplanJob j where j.status = :status and j.updatedAt > :from and j.updatedAt < :to")
    List<Long> findIdsByStatusAndUpdatedAtBetween(
            @Param("status") JobStatus status, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    List<TripReplanJob> findByUserAndTripAndIndoorOnlyAndStatusInAndUpdatedAtAfter(
            User user, Trip trip, boolean indoorOnly, List<JobStatus> statuses, LocalDateTime updatedAfter);

    List<TripReplanJob> findByUserAndTripAndIndoorOnlyAndAllPlacesAndStatusInAndUpdatedAtAfter(
            User user, Trip trip, boolean indoorOnly, boolean allPlaces, List<JobStatus> statuses, LocalDateTime updatedAfter);
}
