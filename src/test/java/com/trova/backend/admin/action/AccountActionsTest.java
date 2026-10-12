package com.trova.backend.admin.action;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.SuspendedUsers;
import com.trova.backend.service.ShareKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

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

    private static int status(Throwable e) {
        return ((AdminActionException) e).status().value();
    }

    @Test
    void 정지하면_단축어_키를_지우고_정지_목록에_넣는다() {
        User user = userRepository.save(new User("google", "acct-s1", "정지대상", null));
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

        userRepository.deleteById(user.getId());
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
        User user = userRepository.save(new User("google", "acct-d1", "삭제대상", null));
        suspend.execute(user.getId(), 1L);

        ActionPreview preview = deleteAccount.preview(user.getId(), 1L);
        assertThat(preview.confirmText()).isEqualTo("계정 삭제");
        assertThat(preview.impact()).isEqualTo("계정 1·영상 0·장소 0·여행 0·초안 0");

        assertThat(deleteAccount.execute(user.getId(), 1L)).isEqualTo("계정 1·영상 0·장소 0·여행 0·초안 0");
        assertThat(userRepository.findById(user.getId())).isEmpty();
        assertThat(suspendedUsers.isSuspended(user.getId())).isFalse();
    }

    @Test
    void 이미_정지된_계정을_또_정지하면_409() {
        User user = userRepository.save(new User("google", "acct-s2", "정지대상", null));
        suspend.execute(user.getId(), 1L);

        assertThatThrownBy(() -> suspend.preview(user.getId(), 1L))
                .satisfies(e -> assertThat(status(e)).isEqualTo(409));
        assertThatThrownBy(() -> suspend.execute(user.getId(), 1L))
                .satisfies(e -> assertThat(status(e)).isEqualTo(409));

        unsuspend.execute(user.getId(), 1L);
        userRepository.deleteById(user.getId());
    }

    @Test
    void 정지되지_않은_계정을_풀면_409() {
        User user = userRepository.save(new User("google", "acct-u1", "보통", null));

        assertThatThrownBy(() -> unsuspend.preview(user.getId(), 1L))
                .satisfies(e -> assertThat(status(e)).isEqualTo(409));

        userRepository.deleteById(user.getId());
    }

    @Test
    void 없는_계정은_404() {
        assertThatThrownBy(() -> suspend.preview(999_999L, 1L)).satisfies(e -> assertThat(status(e)).isEqualTo(404));
        assertThatThrownBy(() -> unsuspend.preview(999_999L, 1L)).satisfies(e -> assertThat(status(e)).isEqualTo(404));
        assertThatThrownBy(() -> deleteAccount.preview(999_999L, 1L)).satisfies(e -> assertThat(status(e)).isEqualTo(404));
    }
}
