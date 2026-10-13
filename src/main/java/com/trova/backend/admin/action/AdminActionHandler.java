package com.trova.backend.admin.action;

public interface AdminActionHandler {

    AdminAction action();

    /** 대상이 없으면 AdminActionException.notFound, 조건이 안 맞으면 AdminActionException.conflict(사유). */
    ActionPreview preview(Long targetId, Long adminUserId);

    /** 실행하고 결과 한 줄(건수)을 돌려준다. preview와 같은 검사를 다시 한다. */
    String execute(Long targetId, Long adminUserId);
}
