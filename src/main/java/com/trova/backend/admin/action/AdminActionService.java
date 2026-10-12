package com.trova.backend.admin.action;

import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** 미리보기 → 확인 토큰 → 사유·확인 글자 → 실행 → 감사 기록(설계 2단계 안전장치 1~5). */
@Service
public class AdminActionService {

    public static final int REASON_MAX = 300;
    static final String TOKEN_EXPIRED = "확인이 만료됐어요. 미리보기부터 다시 해 주세요.";

    public record Previewed(ActionPreview preview, String confirmToken, long expiresInSeconds) { }

    public record Executed(Long targetId, String result) { }

    private final Map<AdminAction, AdminActionHandler> handlers = new EnumMap<>(AdminAction.class);
    private final ConfirmTokenStore confirmTokens;
    private final AdminAuditLog auditLog;

    public AdminActionService(List<AdminActionHandler> handlers, ConfirmTokenStore confirmTokens, AdminAuditLog auditLog) {
        for (AdminActionHandler handler : handlers) {
            // 같은 조치를 둘이 맡으면 어느 쪽이 실행될지 모르게 된다 — 시작부터 막는다.
            if (this.handlers.putIfAbsent(handler.action(), handler) != null) {
                throw new IllegalStateException("관리자 조치 핸들러가 중복됐습니다: " + handler.action());
            }
        }
        this.confirmTokens = confirmTokens;
        this.auditLog = auditLog;
    }

    /** 경로의 소문자·하이픈 이름(`delete-trip`)을 조치로 바꾼다. 모르면 빈 값. */
    public static Optional<AdminAction> parse(String path) {
        try {
            return Optional.of(AdminAction.valueOf(path.toUpperCase(Locale.ROOT).replace('-', '_')));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Previewed preview(AdminAction action, Long targetId, Long adminUserId) {
        ActionPreview preview = handler(action).preview(targetId, adminUserId);
        String token = confirmTokens.issue(new ConfirmTokenStore.Pending(
                adminUserId, action, targetId, preview.targetUserId(), preview.confirmText()));
        return new Previewed(preview, token, ConfirmTokenStore.TTL.toSeconds());
    }

    /** 확인 토큰은 꺼내는 순간 지워진다 — 사유·확인 글자가 틀려 400이 나도 미리보기부터 다시 한다. */
    public Executed execute(AdminAction action, String confirmToken, String reason, String confirmText, Long adminUserId) {
        ConfirmTokenStore.Pending pending = confirmTokens.consume(confirmToken)
                .filter(p -> p.adminUserId().equals(adminUserId) && p.action() == action)
                .orElseThrow(() -> AdminActionException.conflict(TOKEN_EXPIRED));
        String trimmedReason = reason == null ? "" : reason.strip();
        if (trimmedReason.isEmpty() || trimmedReason.length() > REASON_MAX) {
            throw AdminActionException.badRequest("사유를 1~300자로 적어 주세요.");
        }
        if (pending.confirmText() != null
                && (confirmText == null || !pending.confirmText().strip().equals(confirmText.strip()))) {
            throw AdminActionException.badRequest("확인 글자가 달라요: " + pending.confirmText());
        }
        String result = handler(action).execute(pending.targetId(), adminUserId);
        auditLog.record(adminUserId, action.name(), pending.targetId(), pending.targetUserId(), trimmedReason, result);
        return new Executed(pending.targetId(), result);
    }

    private AdminActionHandler handler(AdminAction action) {
        AdminActionHandler handler = handlers.get(action);
        if (handler == null) {
            throw AdminActionException.notFound();
        }
        return handler;
    }
}
