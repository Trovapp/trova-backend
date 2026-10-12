package com.trova.backend.admin.action;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.SuspendedUsers;
import com.trova.backend.service.AdminAccessService;
import com.trova.backend.service.UserAccountService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 사용자 요청 시 계정과 딸린 데이터를 지운다 — 앱의 탈퇴와 같은 경로(UserAccountService.withdraw). */
@Component
public class DeleteAccountAction implements AdminActionHandler {

    static final String CONFIRM_TEXT = "계정 삭제";

    private final UserRepository userRepository;
    private final AdminAccessService adminAccessService;
    private final UserAccountService userAccountService;
    private final SuspendedUsers suspendedUsers;
    private final UserImpact userImpact;

    public DeleteAccountAction(UserRepository userRepository, AdminAccessService adminAccessService,
                               UserAccountService userAccountService, SuspendedUsers suspendedUsers,
                               UserImpact userImpact) {
        this.userRepository = userRepository;
        this.adminAccessService = adminAccessService;
        this.userAccountService = userAccountService;
        this.suspendedUsers = suspendedUsers;
        this.userImpact = userImpact;
    }

    @Override
    public AdminAction action() {
        return AdminAction.DELETE_ACCOUNT;
    }

    @Override
    @Transactional(readOnly = true)
    public ActionPreview preview(Long targetId, Long adminUserId) {
        User user = check(targetId);
        return new ActionPreview(action(), user.getId(), user.getId(), impact(user), CONFIRM_TEXT);
    }

    @Override
    @Transactional
    public String execute(Long targetId, Long adminUserId) {
        User user = check(targetId);
        String impact = impact(user);
        userAccountService.withdraw(user);
        suspendedUsers.remove(user.getId());
        return impact;
    }

    private User check(Long targetId) {
        User user = userRepository.findById(targetId).orElseThrow(AdminActionException::notFound);
        if (adminAccessService.isAdmin(user)) {
            throw AdminActionException.conflict("관리자 계정은 삭제할 수 없어요.");
        }
        return user;
    }

    private String impact(User user) {
        UserImpact.Counts counts = userImpact.count(user);
        return "계정 1·영상 " + counts.jobs() + "·장소 " + counts.savedPlaces()
                + "·여행 " + counts.trips() + "·초안 " + counts.drafts();
    }
}
