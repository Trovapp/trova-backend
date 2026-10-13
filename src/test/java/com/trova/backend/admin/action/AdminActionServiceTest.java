package com.trova.backend.admin.action;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminActionServiceTest {

    private static AdminActionHandler handlerFor(AdminAction action) {
        return new AdminActionHandler() {
            @Override
            public AdminAction action() {
                return action;
            }

            @Override
            public ActionPreview preview(Long targetId, Long adminUserId) {
                return null;
            }

            @Override
            public String execute(Long targetId, Long adminUserId) {
                return null;
            }
        };
    }

    @Test
    void 같은_조치를_맡은_핸들러가_둘이면_시작부터_실패한다() {
        List<AdminActionHandler> handlers = List.of(handlerFor(AdminAction.HIDE_DRAFT), handlerFor(AdminAction.HIDE_DRAFT));

        assertThatThrownBy(() -> new AdminActionService(handlers, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HIDE_DRAFT");
    }
}
