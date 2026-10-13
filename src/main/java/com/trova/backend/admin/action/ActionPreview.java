package com.trova.backend.admin.action;

/**
 * 조치 미리보기 결과.
 * targetUserId: 대상의 주인. impact: 화면에 보일 영향 범위(예 "여행 1·일차 3·장소 12").
 * confirmText: 다시 입력해야 할 글자(없으면 null).
 */
public record ActionPreview(AdminAction action, Long targetId, Long targetUserId, String impact, String confirmText) {
}
