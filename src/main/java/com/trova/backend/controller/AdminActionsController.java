package com.trova.backend.controller;

import com.trova.backend.admin.AdminGuard;
import com.trova.backend.admin.action.ActionPreview;
import com.trova.backend.admin.action.AdminAction;
import com.trova.backend.admin.action.AdminActionException;
import com.trova.backend.admin.action.AdminActionService;
import com.trova.backend.entity.AdminActionLog;
import com.trova.backend.entity.User;
import com.trova.backend.repository.AdminActionLogRepository;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 조치(#167, 설계 2단계). 미리보기는 읽기라 재인증 없이, 실행은 로그인 10분 안만.
 * 본문은 required = false — 본문 검사(400)보다 관리자 확인(401·403)이 먼저 나야 한다.
 */
@RestController
public class AdminActionsController {

    private static final Duration REAUTH_WINDOW = Duration.ofMinutes(10);

    public record PreviewRequest(Long targetId) { }

    public record PreviewResponse(AdminAction action, Long targetId, Long targetUserId, String impact,
                                  String confirmText, String confirmToken, long expiresInSeconds) { }

    public record ExecuteRequest(String confirmToken, String reason, String confirmText) { }

    public record ExecuteResponse(AdminAction action, Long targetId, String result) { }

    public record LogItem(Long id, LocalDateTime createdAt, Long actorUserId, String action, Long targetId,
                          Long targetUserId, String reason, String result) { }

    public record LogsResponse(List<LogItem> logs) { }

    private final AdminGuard adminGuard;
    private final AdminActionService actionService;
    private final AdminActionLogRepository logRepository;

    public AdminActionsController(AdminGuard adminGuard, AdminActionService actionService,
                                  AdminActionLogRepository logRepository) {
        this.adminGuard = adminGuard;
        this.actionService = actionService;
        this.logRepository = logRepository;
    }

    @PostMapping("/api/admin/actions/{action}/preview")
    public PreviewResponse preview(Authentication authentication, @PathVariable String action,
                                   @RequestBody(required = false) PreviewRequest request) {
        User admin = adminGuard.requireAdmin(authentication);
        AdminAction parsed = AdminActionService.parse(action).orElseThrow(AdminActionException::notFound);
        if (request == null || request.targetId() == null) {
            throw AdminActionException.badRequest("대상 번호가 없어요.");
        }
        AdminActionService.Previewed previewed = actionService.preview(parsed, request.targetId(), admin.getId());
        ActionPreview p = previewed.preview();
        return new PreviewResponse(p.action(), p.targetId(), p.targetUserId(), p.impact(), p.confirmText(),
                previewed.confirmToken(), previewed.expiresInSeconds());
    }

    @PostMapping("/api/admin/actions/{action}/execute")
    public ExecuteResponse execute(Authentication authentication, @PathVariable String action,
                                   @RequestBody(required = false) ExecuteRequest request) {
        User admin = adminGuard.requireAdmin(authentication);
        adminGuard.requireRecentLogin(authentication, REAUTH_WINDOW);
        AdminAction parsed = AdminActionService.parse(action).orElseThrow(AdminActionException::notFound);
        if (request == null) {
            throw AdminActionException.badRequest("요청 본문이 없어요.");
        }
        AdminActionService.Executed executed = actionService.execute(parsed, request.confirmToken(), request.reason(),
                request.confirmText(), admin.getId());
        return new ExecuteResponse(parsed, executed.targetId(), executed.result());
    }

    @GetMapping("/api/admin/actions")
    public LogsResponse logs(Authentication authentication, @RequestParam(required = false) Long targetUserId) {
        adminGuard.requireAdmin(authentication);
        List<AdminActionLog> logs = targetUserId == null
                ? logRepository.findTop50ByOrderByIdDesc()
                : logRepository.findTop50ByTargetUserIdOrderByIdDesc(targetUserId);
        return new LogsResponse(logs.stream().map(l -> new LogItem(l.getId(), l.getCreatedAt(), l.getActorUserId(),
                l.getAction(), l.getTargetId(), l.getTargetUserId(), l.getReason(), l.getResult())).toList());
    }
}
