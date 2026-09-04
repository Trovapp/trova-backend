package com.trova.backend.repository;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, Long> {
    List<ProcessingJob> findByUserOrderByCreatedAtDescIdDesc(User user);
    List<ProcessingJob> findByUserAndStatusIn(User user, List<JobStatus> statuses);
    List<ProcessingJob> findByUserAndSourceUrlAndStatusIn(User user, String sourceUrl, List<JobStatus> statuses);
    void deleteByUser(User user);
}
