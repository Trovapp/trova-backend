package com.trova.backend.admin.action;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.SuspendedUsers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 계정 정지 풀기(ADR 0014). 지운 단축어 키는 되살리지 않는다 — 사용자가 앱에서 다시 발급한다. */
@Component
public class UnsuspendUserAction implements AdminActionHandler {

    private final UserRepository userRepository;
    private final SuspendedUsers suspendedUsers;

    public UnsuspendUserAction(UserRepository userRepository, SuspendedUsers suspendedUsers) {
        this.userRepository = userRepository;
        this.suspendedUsers = suspendedUsers;
    }

    @Override
    public AdminAction action() {
        return AdminAction.UNSUSPEND_USER;
    }

    @Override
    @Transactional(readOnly = true)
    public ActionPreview preview(Long targetId, Long adminUserId) {
        User user = check(targetId);
        return new ActionPreview(action(), user.getId(), user.getId(), "계정 1", null);
    }

    @Override
    @Transactional
    public String execute(Long targetId, Long adminUserId) {
        User user = check(targetId);
        user.unsuspend();
        suspendedUsers.remove(user.getId());
        return "정지 풀기";
    }

    private User check(Long targetId) {
        User user = userRepository.findById(targetId).orElseThrow(AdminActionException::notFound);
        if (!user.isSuspended()) {
            throw AdminActionException.conflict("정지된 계정이 아니에요.");
        }
        return user;
    }
}
