package com.trova.backend.admin.action;

import com.trova.backend.entity.AdminActionLog;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.User;
import com.trova.backend.queue.ShareJobQueue;
import com.trova.backend.repository.AdminActionLogRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.SuspendedUsers;
import com.trova.backend.service.ShareKeyService;
import com.trova.backend.service.UserAccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/** 실제 JPA 트랜잭션. 테스트 메서드는 트랜잭션 밖에서 커밋/롤백 결과를 다시 읽는다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:admin-action-tx;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminActionTransactionTest {
    @Autowired private AdminActionService actions;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripDraftRepository drafts;
    @Autowired private ProcessingJobRepository jobs;
    @Autowired private AdminActionLogRepository logs;
    @Autowired private ShareKeyService keys;
    @Autowired private SuspendedUsers suspended;
    @Autowired private SuspendUserAction suspend;
    @Autowired private UserAccountService accounts;
    @MockitoSpyBean private AdminAuditLog audit;
    @MockitoBean private ShareJobQueue queue;
    private User user;

    @BeforeEach
    void 준비() {
        String sub = "atomic-" + UUID.randomUUID();
        user = users.save(new User("google", sub, "대상", null));
    }

    @AfterEach
    void 정리() {
        reset(auditTarget());
        suspended.remove(user.getId());
        users.findById(user.getId()).ifPresent(accounts::withdraw);
    }

    private List<AdminActionLog> userLogs() {
        return logs.findAll().stream().filter(log -> user.getId().equals(log.getTargetUserId())).toList();
    }

    private AdminActionService.Executed execute(AdminAction action, Long id) {
        AdminActionService.Previewed preview = actions.preview(action, id, 99L);
        return actions.execute(action, preview.confirmToken(), "검증", preview.preview().confirmText(), 99L);
    }

    private AdminAuditLog auditTarget() {
        return AopTestUtils.getUltimateTargetObject(audit);
    }

    private void failAudit() {
        doThrow(new IllegalStateException("injected audit failure"))
                .when(auditTarget()).recordInTransaction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 감사_실패는_초안_숨김을_롤백한다() {
        TripDraft draft = drafts.save(TripDraft.auto(user, List.of(), "fixture"));
        failAudit();
        assertThatThrownBy(() -> execute(AdminAction.HIDE_DRAFT, draft.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(drafts.findById(draft.getId()).orElseThrow().getDismissedAt()).isNull();
        assertThat(userLogs()).isEmpty();
    }

    @Test
    void 감사_실패는_여행과_계정_삭제를_롤백한다() {
        Trip trip = trips.save(new Trip(user, "fixture", LocalDate.now(), LocalDate.now()));
        failAudit();
        assertThatThrownBy(() -> execute(AdminAction.DELETE_TRIP, trip.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(trips.findById(trip.getId())).isPresent();
        assertThatThrownBy(() -> execute(AdminAction.DELETE_ACCOUNT, user.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(users.findById(user.getId())).isPresent();
        assertThat(trips.findById(trip.getId())).isPresent();
        assertThat(userLogs()).isEmpty();
    }

    @Test
    void 감사_실패는_정지와_키_삭제와_메모리_정지를_남기지_않는다() {
        keys.issue(user.getId());
        failAudit();
        assertThatThrownBy(() -> execute(AdminAction.SUSPEND_USER, user.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(users.findById(user.getId()).orElseThrow().isSuspended()).isFalse();
        assertThat(keys.status(user.getId()).exists()).isTrue();
        assertThat(suspended.isSuspended(user.getId())).isFalse();
        assertThat(userLogs()).isEmpty();
    }

    @Test
    void 감사_실패는_정지_풀기와_메모리_해제를_남기지_않는다() {
        suspend.execute(user.getId(), 99L);
        failAudit();
        assertThatThrownBy(() -> execute(AdminAction.UNSUSPEND_USER, user.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(users.findById(user.getId()).orElseThrow().isSuspended()).isTrue();
        assertThat(suspended.isSuspended(user.getId())).isTrue();
        assertThat(userLogs()).isEmpty();
    }

    @Test
    void 정상_정지는_키와_DB와_감사를_커밋하고_메모리에_반영한다() {
        keys.issue(user.getId());
        execute(AdminAction.SUSPEND_USER, user.getId());
        assertThat(users.findById(user.getId()).orElseThrow().isSuspended()).isTrue();
        assertThat(keys.status(user.getId()).exists()).isFalse();
        assertThat(suspended.isSuspended(user.getId())).isTrue();
        assertThat(userLogs()).hasSize(1);
        assertThat(userLogs().get(0).getAction()).isEqualTo("SUSPEND_USER");
    }

    private ProcessingJob failedJob() {
        ProcessingJob job = new ProcessingJob(user, "https://www.youtube.com/shorts/fixture-" + user.getId(), SourcePlatform.YOUTUBE);
        job.markFailed(ProcessingJob.AI_QUOTA_MESSAGE);
        return jobs.save(job);
    }

    @Test
    void 재실행_감사_실패는_새_작업을_롤백하고_큐를_부르지_않는다() {
        ProcessingJob original = failedJob();
        failAudit();
        assertThatThrownBy(() -> execute(AdminAction.RETRY_JOB, original.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(jobs.findByUserAndSourceUrlAndStatusIn(user, original.getSourceUrl(),
                List.of(com.trova.backend.entity.JobStatus.PENDING))).isEmpty();
        verify(queue, never()).enqueue(anyLong());
        assertThat(userLogs()).isEmpty();
    }

    @Test
    void 재실행_큐는_다른_스레드에서도_생성과_감사_커밋을_읽는다() {
        ProcessingJob original = failedJob();
        doAnswer(call -> {
            Long id = call.getArgument(0);
            try (ExecutorService reader = Executors.newSingleThreadExecutor()) {
                reader.submit(() -> {
                    assertThat(jobs.findById(id)).isPresent();
                    assertThat(userLogs()).hasSize(1);
                    assertThat(userLogs().get(0).getResult()).isEqualTo("새 작업 #" + id);
                }).get(5, TimeUnit.SECONDS);
            }
            return null;
        }).when(queue).enqueue(anyLong());
        AdminActionService.Executed result = execute(AdminAction.RETRY_JOB, original.getId());
        Long newId = Long.valueOf(result.result().substring("새 작업 #".length()));
        verify(queue).enqueue(newId);
        assertThat(jobs.findById(newId)).isPresent();
    }

    @Test
    void 커밋후_큐_거절은_새_작업을_지우고_생성_및_거절_감사를_남긴다() {
        ProcessingJob original = failedJob();
        doThrow(new TaskRejectedException("full")).when(queue).enqueue(anyLong());
        assertThatThrownBy(() -> execute(AdminAction.RETRY_JOB, original.getId()))
                .isInstanceOf(AdminActionException.class)
                .satisfies(error -> assertThat(((AdminActionException) error).status().value()).isEqualTo(409));
        assertThat(jobs.findByUserAndSourceUrlAndStatusIn(user, original.getSourceUrl(),
                List.of(com.trova.backend.entity.JobStatus.PENDING))).isEmpty();
        assertThat(userLogs()).hasSize(2);
        assertThat(userLogs()).anySatisfy(log -> assertThat(log.getResult()).contains("큐 접수 거절"));
    }

    @Test
    void 실제_감사_DB_제약_오류도_조치_변경을_롤백한다() {
        TripDraft draft = drafts.save(TripDraft.auto(user, List.of(), "fixture"));
        doAnswer(call -> {
            logs.saveAndFlush(new AdminActionLog(99L, "HIDE_DRAFT", draft.getId(), user.getId(), "x".repeat(501), "fixture"));
            return null;
        }).when(auditTarget()).recordInTransaction(any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> execute(AdminAction.HIDE_DRAFT, draft.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(drafts.findById(draft.getId()).orElseThrow().getDismissedAt()).isNull();
        assertThat(userLogs()).isEmpty();
    }
}
