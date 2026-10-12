package com.trova.backend.admin.action;

import com.trova.backend.entity.AdminActionLog;
import com.trova.backend.repository.AdminActionLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AdminAuditLogTest {

    @Autowired
    private AdminAuditLog auditLog;

    @Autowired
    private AdminActionLogRepository repository;

    @Test
    void 대상_사용자별로_최근_기록을_모은다() {
        auditLog.record(1L, AdminActionLog.ADMIN_USER_VIEW, 900L, 900L, null, null);
        auditLog.record(1L, "DELETE_TRIP", 55L, 900L, "사용자 요청", "여행 1·일차 2·장소 5");
        auditLog.record(1L, AdminActionLog.ADMIN_USER_VIEW, 901L, 901L, null, null);

        List<AdminActionLog> logs = repository.findTop50ByTargetUserIdOrderByIdDesc(900L);
        assertThat(logs).extracting(AdminActionLog::getAction).containsExactly("DELETE_TRIP", AdminActionLog.ADMIN_USER_VIEW);
        assertThat(logs.get(0).getTargetId()).isEqualTo(55L);
    }
}
