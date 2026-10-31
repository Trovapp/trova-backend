package com.trova.backend.admin.action;

import com.trova.backend.queue.ShareJobQueue;
import com.trova.backend.repository.ProcessingJobRepository;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 생성 커밋 뒤 전달. afterCommit 실패 정리는 끝난 트랜잭션에 참여하지 않고 새 커밋을 쓴다. */
@Service
public class AdminRetryJobDispatcher {
    private final ShareJobQueue queue;
    private final ProcessingJobRepository jobs;
    private final AdminAuditLog auditLog;
    private final TransactionTemplate cleanup;

    public AdminRetryJobDispatcher(ShareJobQueue queue, ProcessingJobRepository jobs,
                                   AdminAuditLog auditLog, PlatformTransactionManager transactionManager) {
        this.queue = queue;
        this.jobs = jobs;
        this.auditLog = auditLog;
        this.cleanup = new TransactionTemplate(transactionManager);
        this.cleanup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void enqueue(Long newJobId, Long originalJobId, Long userId, Long adminUserId) {
        try {
            queue.enqueue(newJobId);
        } catch (TaskRejectedException error) {
            cleanup.executeWithoutResult(status -> {
                jobs.deleteById(newJobId);
                auditLog.recordInTransaction(adminUserId, AdminAction.RETRY_JOB.name(), originalJobId, userId,
                        null, "큐 접수 거절 · 새 작업 #" + newJobId + " 삭제");
            });
            throw AdminActionException.conflict("서버가 바빠요. 잠시 뒤 다시 해 주세요.");
        }
    }
}
