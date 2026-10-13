package com.trova.backend.admin.action;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.SuspendedUsers;
import com.trova.backend.service.AdminAccessService;
import com.trova.backend.service.ShareKeyService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 계정 정지(ADR 0014) — 단축어 키를 지우고, 들고 있는 앱 토큰도 다음 요청부터 401이 되게 한다. */
@Component
public class SuspendUserAction implements AdminActionHandler {

    private final UserRepository userRepository;
    private final AdminAccessService adminAccessService;
    private final ShareKeyService shareKeyService;
    private final SuspendedUsers suspendedUsers;

    public SuspendUserAction(UserRepository userRepository, AdminAccessService adminAccessService,
                             ShareKeyService shareKeyService, SuspendedUsers suspendedUsers) {
        this.userRepository = userRepository;
        this.adminAccessService = adminAccessService;
        this.shareKeyService = shareKeyService;
        this.suspendedUsers = suspendedUsers;
    }

    @Override
    public AdminAction action() {
        return AdminAction.SUSPEND_USER;
    }

    @Override
    @Transactional(readOnly = true)
    public ActionPreview preview(Long targetId, Long adminUserId) {
        User user = check(targetId);
        return new ActionPreview(action(), user.getId(), user.getId(), "계정 1·단축어 키 " + keyCount(user), null);
    }

    @Override
    @Transactional
    public String execute(Long targetId, Long adminUserId) {
        User user = check(targetId);
        int keys = keyCount(user);
        user.suspend();
        shareKeyService.revoke(user.getId());
        // 트랜잭션이 끝나기 전에 메모리 목록에 넣는다. 커밋이 실패해 되돌려져도 메모리에만 남는 것은
        // "다음 요청 401"이라 안전한 쪽이고, 서버를 다시 띄우면 DB 기준으로 다시 채워진다.
        suspendedUsers.add(user.getId());
        return "정지·단축어 키 " + keys + "개 삭제";
    }

    /** 관리자 계정이거나 이미 정지된 계정이면 409 — 그래서 suspend()가 두 번 불리지 않는다. */
    private User check(Long targetId) {
        User user = userRepository.findById(targetId).orElseThrow(AdminActionException::notFound);
        if (adminAccessService.isAdmin(user)) {
            throw AdminActionException.conflict("관리자 계정은 정지할 수 없어요.");
        }
        if (user.isSuspended()) {
            throw AdminActionException.conflict("이미 정지된 계정이에요.");
        }
        return user;
    }

    private int keyCount(User user) {
        return shareKeyService.status(user.getId()).exists() ? 1 : 0;
    }
}
