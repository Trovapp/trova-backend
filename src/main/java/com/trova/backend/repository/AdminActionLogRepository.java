package com.trova.backend.repository;

import com.trova.backend.entity.AdminActionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AdminActionLogRepository extends JpaRepository<AdminActionLog, Long> {

    List<AdminActionLog> findTop50ByOrderByIdDesc();

    List<AdminActionLog> findTop50ByTargetUserIdOrderByIdDesc(Long targetUserId);

    List<AdminActionLog> findTop50ByActionInOrderByIdDesc(Collection<String> actions);

    List<AdminActionLog> findTop50ByTargetUserIdAndActionInOrderByIdDesc(Long targetUserId, Collection<String> actions);
}
