package com.trova.backend.repository;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripReplanJobRepository extends JpaRepository<TripReplanJob, Long> {
    List<TripReplanJob> findByUserAndTripAndIndoorOnlyAndStatusIn(
            User user, Trip trip, boolean indoorOnly, List<JobStatus> statuses);
}
