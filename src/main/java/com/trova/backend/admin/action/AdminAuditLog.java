package com.trova.backend.admin.action;

import com.trova.backend.entity.AdminActionLog;
import com.trova.backend.repository.AdminActionLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 조회·조치 감사 기록(ADR 0012·0014). 개인정보 없이 내부 번호·건수만 넘긴다. */
@Service
public class AdminAuditLog {

    private final AdminActionLogRepository repository;

    public AdminAuditLog(AdminActionLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long actorId, String action, Long targetId, Long targetUserId, String reason, String result) {
        repository.save(new AdminActionLog(actorId, action, targetId, targetUserId, reason, result));
    }
}
