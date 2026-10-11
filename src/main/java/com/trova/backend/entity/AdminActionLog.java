package com.trova.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 관리자 감사 기록(ADR 0012). 개인정보(로그인 ID·닉네임·이메일)를 남기지 않고 내부 번호만 — 계정 삭제 뒤에도 남는 기록이라서.
 * 지금(#163)은 관리자 웹 로그인 성공·거부만 남기고, 2단계 관리자 조치(삭제·재실행)가 대상·사유·결과 칸을 쓴다.
 * 앱에서 수정·삭제하는 경로를 만들지 않는다.
 */
@Entity
@Table(name = "admin_action_logs", indexes = @Index(name = "idx_admin_action_logs_created", columnList = "created_at"))
public class AdminActionLog {

    public static final String ADMIN_WEB_LOGIN = "ADMIN_WEB_LOGIN";
    public static final String ADMIN_WEB_LOGIN_DENIED = "ADMIN_WEB_LOGIN_DENIED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "actor_user_id", updatable = false)
    private Long actorUserId;

    @Column(nullable = false, length = 40, updatable = false)
    private String action;

    @Column(name = "target_id", updatable = false)
    private Long targetId;

    @Column(length = 500, updatable = false)
    private String reason;

    @Column(length = 500, updatable = false)
    private String result;

    protected AdminActionLog() {
    }

    public AdminActionLog(Long actorUserId, String action, Long targetId, String reason, String result) {
        this.createdAt = LocalDateTime.now();
        this.actorUserId = actorUserId;
        this.action = action;
        this.targetId = targetId;
        this.reason = reason;
        this.result = result;
    }

    public Long getId() { return id; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Long getActorUserId() { return actorUserId; }
    public String getAction() { return action; }
    public Long getTargetId() { return targetId; }
    public String getReason() { return reason; }
    public String getResult() { return result; }
}
