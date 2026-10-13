package com.trova.backend.admin.action;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.SuspendedUsers;
import com.trova.backend.service.ShareKeyService;
import com.trova.backend.service.UserAccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = "app.admin.users=google:acct-admin")
class AccountActionsTest {

    @Autowired
    private SuspendUserAction suspend;

    @Autowired
    private UnsuspendUserAction unsuspend;

    @Autowired
    private DeleteAccountAction deleteAccount;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SuspendedUsers suspendedUsers;

    @Autowired
    private ShareKeyService shareKeyService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private SavedPlaceRepository savedPlaceRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private TripDraftRepository tripDraftRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Long> createdUserIds = new ArrayList<>();

    /** 이 클래스는 @Transactional이 아니다 — 단언이 실패해도 만든 회원과 딸린 데이터가 남지 않게 지운다. */
    @AfterEach
    void cleanUp() {
        for (Long id : createdUserIds) {
            suspendedUsers.remove(id);
            userRepository.findById(id).ifPresent(userAccountService::withdraw);
        }
    }

    private User newUser() {
        User user = userRepository.save(new User("google", "acct-" + UUID.randomUUID(), "대상", null));
        createdUserIds.add(user.getId());
        return user;
    }

    private static int status(Throwable e) {
        return ((AdminActionException) e).status().value();
    }

    /** 바깥 트랜잭션을 되돌려 조치의 커밋이 실패한 경우를 만든다(조치의 @Transactional은 바깥에 합류). */
    private void inRolledBackTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            work.run();
            tx.setRollbackOnly();
        });
    }

    @Test
    void 정지하면_단축어_키를_지우고_정지_목록에_넣는다() {
        User user = newUser();
        shareKeyService.issue(user.getId());

        assertThat(suspend.preview(user.getId(), 1L).impact()).isEqualTo("계정 1·단축어 키 1");
        assertThat(suspend.preview(user.getId(), 1L).confirmText()).isNull();
        String result = suspend.execute(user.getId(), 1L);

        assertThat(result).isEqualTo("정지·단축어 키 1개 삭제");
        assertThat(userRepository.findById(user.getId()).orElseThrow().isSuspended()).isTrue();
        assertThat(shareKeyService.status(user.getId()).exists()).isFalse();
        assertThat(suspendedUsers.isSuspended(user.getId())).isTrue();

        assertThat(unsuspend.execute(user.getId(), 1L)).isEqualTo("정지 풀기");
        assertThat(suspendedUsers.isSuspended(user.getId())).isFalse();
        assertThat(userRepository.findById(user.getId()).orElseThrow().isSuspended()).isFalse();
    }

    @Test
    void 관리자_계정은_정지_삭제_미리보기에서_409() {
        User admin = userRepository.findByProviderAndProviderUserId("google", "acct-admin")
                .orElseGet(() -> userRepository.save(new User("google", "acct-admin", "관리자", null)));

        assertThatThrownBy(() -> suspend.preview(admin.getId(), admin.getId()))
                .satisfies(e -> assertThat(status(e)).isEqualTo(409))
                .hasMessage("관리자 계정은 정지할 수 없어요.");
        assertThatThrownBy(() -> deleteAccount.preview(admin.getId(), admin.getId()))
                .satisfies(e -> assertThat(status(e)).isEqualTo(409))
                .hasMessage("관리자 계정은 삭제할 수 없어요.");
        assertThatThrownBy(() -> suspend.execute(admin.getId(), admin.getId()))
                .satisfies(e -> assertThat(status(e)).isEqualTo(409));
        assertThat(userRepository.findById(admin.getId()).orElseThrow().isSuspended()).isFalse();
    }

    @Test
    void 계정_삭제는_확인_글자를_요구하고_회원을_지운다() {
        User user = newUser();
        suspend.execute(user.getId(), 1L);

        ActionPreview preview = deleteAccount.preview(user.getId(), 1L);
        assertThat(preview.confirmText()).isEqualTo("계정 삭제");
        assertThat(preview.impact()).isEqualTo("계정 1·영상 0·장소 0·여행 0·초안 0");

        assertThat(deleteAccount.execute(user.getId(), 1L)).isEqualTo("계정 1·영상 0·장소 0·여행 0·초안 0");
        assertThat(userRepository.findById(user.getId())).isEmpty();
        assertThat(suspendedUsers.isSuspended(user.getId())).isFalse();
    }

    @Test
    void 계정_삭제_영향_범위는_영상_장소_여행_초안을_각각_센다() {
        User user = newUser();
        ProcessingJob job1 = processingJobRepository.save(new ProcessingJob(user, "https://youtu.be/i1", SourcePlatform.YOUTUBE));
        processingJobRepository.save(new ProcessingJob(user, "https://youtu.be/i2", SourcePlatform.YOUTUBE));
        for (int i = 0; i < 3; i++) {
            savedPlaceRepository.save(new SavedPlace(job1, user, "장소" + i, null, "cafe", 35.0, 129.0));
        }
        for (int i = 0; i < 4; i++) {
            tripRepository.save(new Trip(user, "여행" + i, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)));
        }
        for (int i = 0; i < 5; i++) {
            tripDraftRepository.save(new TripDraft(user, List.of(job1.getId()), "초안" + i));
        }
        String expected = "계정 1·영상 2·장소 3·여행 4·초안 5";

        assertThat(deleteAccount.preview(user.getId(), 1L).impact()).isEqualTo(expected);
        assertThat(deleteAccount.execute(user.getId(), 1L)).isEqualTo(expected);
        assertThat(userRepository.findById(user.getId())).isEmpty();
    }

    @Test
    void 이미_정지된_계정을_또_정지하면_409() {
        User user = newUser();
        suspend.execute(user.getId(), 1L);

        assertThatThrownBy(() -> suspend.preview(user.getId(), 1L))
                .satisfies(e -> assertThat(status(e)).isEqualTo(409));
        assertThatThrownBy(() -> suspend.execute(user.getId(), 1L))
                .satisfies(e -> assertThat(status(e)).isEqualTo(409));
    }

    @Test
    void 정지되지_않은_계정을_풀면_409() {
        User user = newUser();

        assertThatThrownBy(() -> unsuspend.preview(user.getId(), 1L))
                .satisfies(e -> assertThat(status(e)).isEqualTo(409));
    }

    @Test
    void 없는_계정은_404() {
        assertThatThrownBy(() -> suspend.preview(999_999L, 1L)).satisfies(e -> assertThat(status(e)).isEqualTo(404));
        assertThatThrownBy(() -> unsuspend.preview(999_999L, 1L)).satisfies(e -> assertThat(status(e)).isEqualTo(404));
        assertThatThrownBy(() -> deleteAccount.preview(999_999L, 1L)).satisfies(e -> assertThat(status(e)).isEqualTo(404));
    }

    @Test
    void 정지_풀기_커밋이_실패하면_정지_목록에_남는다() {
        User user = newUser();
        suspend.execute(user.getId(), 1L);

        inRolledBackTransaction(() -> unsuspend.execute(user.getId(), 1L));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isSuspended()).isTrue();
        assertThat(suspendedUsers.isSuspended(user.getId())).isTrue();
    }

    @Test
    void 계정_삭제_커밋이_실패하면_정지_목록에_남는다() {
        User user = newUser();
        suspend.execute(user.getId(), 1L);

        inRolledBackTransaction(() -> deleteAccount.execute(user.getId(), 1L));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isSuspended()).isTrue();
        assertThat(suspendedUsers.isSuspended(user.getId())).isTrue();
    }
}
