package com.trova.backend.admin.action;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** DB 조치와 실행 감사의 커밋 경계. 입력 검사는 호출자가 먼저 끝낸다(ADR 0026). */
@Service
public class AdminActionTransactionService {
    private final AdminAuditLog auditLog;

    public AdminActionTransactionService(AdminAuditLog auditLog) {
        this.auditLog = auditLog;
    }

    @Transactional
    public String execute(AdminActionHandler handler, ConfirmTokenStore.Pending pending, String reason) {
        String result = handler.execute(pending.targetId(), pending.adminUserId());
        auditLog.recordInTransaction(pending.adminUserId(), pending.action().name(), pending.targetId(),
                pending.targetUserId(), reason, result);
        return result;
    }
}
