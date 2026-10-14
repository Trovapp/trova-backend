package com.trova.backend.controller;

import com.trova.backend.admin.AdminGuard;
import com.trova.backend.admin.action.AdminAuditLog;
import com.trova.backend.entity.AdminActionLog;
import com.trova.backend.entity.User;
import com.trova.backend.push.ExpoPushService;
import com.trova.backend.push.PushMessage;
import com.trova.backend.push.PushMessages;
import com.trova.backend.push.PushNotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 관리자 본인 기기로 웹·앱 푸시 테스트(#185, #190). 운영에서 비 소식을 만들 수 없어 실제 문구·이동을 확인하는 용도.
 * 이동 값은 존재하지 않는 번호(0)라 눌렀을 때 화면의 "찾을 수 없어요" 처리도 함께 본다.
 */
@RestController
public class AdminPushController {

    public record TestRequest(String kind) { }

    private final AdminGuard adminGuard;
    private final PushNotificationService pushNotificationService;
    private final ExpoPushService expoPushService;
    private final AdminAuditLog auditLog;

    public AdminPushController(AdminGuard adminGuard, PushNotificationService pushNotificationService,
                               ExpoPushService expoPushService, AdminAuditLog auditLog) {
        this.adminGuard = adminGuard;
        this.pushNotificationService = pushNotificationService;
        this.expoPushService = expoPushService;
        this.auditLog = auditLog;
    }

    @PostMapping("/api/admin/push/test")
    public ResponseEntity<PushNotificationService.Result> test(Authentication authentication,
                                                               @RequestBody(required = false) TestRequest request) {
        User admin = adminGuard.requireAdmin(authentication);
        PushMessage message;
        if (request != null && "draft".equals(request.kind())) {
            message = PushMessages.draftReady(0L, 5, 1);
        } else if (request != null && "rain".equals(request.kind())) {
            message = PushMessages.rain("테스트 여행", LocalDate.now().plusDays(1), 0.7, "테스트 장소", 0L, 0L, 0L);
        } else {
            return ResponseEntity.badRequest().build();
        }
        String kind = "test-" + request.kind();
        PushNotificationService.Result web = pushNotificationService.sendToUser(admin.getId(), kind, message);
        PushNotificationService.Result app = expoPushService.sendToUser(admin.getId(), kind, message);
        auditLog.record(admin.getId(), AdminActionLog.ADMIN_PUSH_TEST, null, admin.getId(), null,
                request.kind() + " 웹 " + web.sent() + "/" + web.removed() + "/" + web.failed()
                        + " 앱 " + app.sent() + "/" + app.removed() + "/" + app.failed());
        return ResponseEntity.ok(new PushNotificationService.Result(web.sent() + app.sent(),
                web.removed() + app.removed(), web.failed() + app.failed()));
    }
}
