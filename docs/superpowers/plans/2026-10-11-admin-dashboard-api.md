# 관리자 대시보드 집계 API·서버 상태 기록 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리자만 부를 수 있는 대시보드 집계 API 5개(`/api/admin/dashboard/{usage,pipeline,ai,server,summary}`)와 5분 서버 상태 스냅샷을 만든다(1단계 PR①, 이슈 #161).

**Architecture:** 컨트롤러 하나(`controller/AdminDashboardController`)가 권한 확인(`admin/AdminGuard`) 뒤 탭별 서비스(`admin/*DashboardService`)를 부른다. 일별 건수는 DB에서 날짜별 `group by`, 처리 시간 백분위·실패 이유처럼 이식성 없는 집계는 기간 안 행을 필요한 열만 읽어 Java에서 계산한다(30일·현재 규모 기준, 30일 행이 10만을 넘으면 다시 본다). 서버 상태는 요청 시간 필터가 모은 값을 5분마다 `server_metric_snapshots`에 저장하고 30일 지나면 지운다.

**Tech Stack:** Spring Boot 4.1, Spring Data JPA(Hibernate 6 HQL), H2(테스트, PostgreSQL 모드)·Supabase Postgres(운영), JUnit 5 + MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-11-admin-dashboard-design.md` (1단계 "백엔드 API", "서버 상태 기록", "보안" 중 PR① 항목), `docs/adr/0012-admin-dashboard-web.md`

## Global Constraints

- 들여쓰기 4칸, 생성자 주입, Lombok 없음, 요청·응답은 `record`, 새 코드에 와일드카드 import·`var` 금지(ADR 0009).
- 테스트 이름은 한글 문장(`void 관리자가_아니면_403이다()`).
- 커밋 메시지 `타입: 내용`만, AI 서명·트레일러 금지. 커밋 전 `./gradlew build`.
- 권한: 비로그인 401(보안 필터), 관리자 아님 403(`AdminAccessService`, 설정 `app.admin.users`).
- 기간 `days`는 7, 14, 30만 — 그 밖은 400. 기본 14.
- 날짜는 서버 기본 시간대 기준(엔티티가 `LocalDateTime.now()`로 저장, 운영 `TZ=Asia/Seoul`).
- 테스트 설정 `src/test/resources/application.yml`은 메인 설정을 통째로 덮으므로, 새 설정값은 전부 `@Value("${...:기본값}")`로 기본값을 둔다.
- 새 설정 기본값(감으로 정함, 실측 아님): `app.instance-name=local`, `app.admin.dashboard.stuck-after-minutes=5`, `app.admin.dashboard.gemini-alert=800`, `app.admin.dashboard.failures-alert=5`, `app.admin.dashboard.p90-alert-ms=1000`, `app.admin.dashboard.gemini-daily-limit=1000`.
- 응답에 사용자 개인정보(닉네임·로그인 ID·영상 링크)를 넣지 않는다 — 작업은 번호·상태만.

## Review Focus

- 데이터가 하나도 없는 기간 — 모든 탭이 0으로 채운 일별 배열을 돌려야 한다(빈 배열·500 아님). Task 1·2·3 테스트에 "데이터 없음" 케이스.
- `days=0`·`days=31`·`days=abc` — 400(500 아님). Task 1 테스트.
- 실패 메시지가 `null`인 FAILED 작업 — "기타"로 세야 한다(NPE 아님). Task 2 테스트.
- 스냅샷이 아직 없을 때(서버 막 시작) — 서버 탭은 빈 목록, 요약 카드의 p90은 `null`·경고 아님. Task 4·5 테스트.
- 로컬 서버가 같은 DB에 쓴 스냅샷 — 운영 대시보드에 섞이면 안 된다(자기 `app.instance-name`만). Task 4 테스트.

---

## File Structure

| 파일 | 책임 |
|---|---|
| `admin/AdminGuard.java` | 관리자 확인(아니면 403) |
| `admin/DashboardPeriod.java` | `days` 검증·기간 계산 |
| `admin/DailyCount.java`, `admin/DailySeries.java` | 일별 건수 record, 빈 날 0 채우기 |
| `admin/DashboardQueries.java` | JPQL 집계 쿼리 모음(EntityManager) |
| `admin/UsageDashboardService.java` | 사용 데이터 탭 |
| `admin/FailureReason.java`, `admin/Percentiles.java` | 실패 이유 분류, 백분위 계산 |
| `admin/PipelineDashboardService.java` | 분석 파이프라인 탭 |
| `admin/AiDashboardService.java` | AI 한도·호출 탭 |
| `entity/ServerMetricSnapshot.java`, `repository/ServerMetricSnapshotRepository.java` | 스냅샷 저장 |
| `admin/RequestTimingFilter.java` | `/api/**` 요청 시간·상태 모으기 |
| `admin/ServerMetricSnapshotRecorder.java` | 5분 저장·30일 정리 |
| `admin/ServerDashboardService.java` | 서버 상태 탭 |
| `admin/DashboardSummaryService.java` | 요약 카드 4개 |
| `controller/AdminDashboardController.java` | 엔드포인트 5개 |
| 테스트: `controller/AdminDashboardControllerTest.java`, `admin/ServerMetricSnapshotRecorderTest.java`, `controller/AdminEndpointsSecurityTest.java`, `admin/FailureReasonTest.java`, `admin/PercentilesTest.java` | |

(경로 앞부분: `src/main/java/com/trova/backend/`, `src/test/java/com/trova/backend/`)

---

### Task 1: 권한·기간·사용 데이터 탭

**Files:**
- Create: `src/main/java/com/trova/backend/admin/AdminGuard.java`, `admin/DashboardPeriod.java`, `admin/DailyCount.java`, `admin/DailySeries.java`, `admin/DashboardQueries.java`, `admin/UsageDashboardService.java`, `controller/AdminDashboardController.java`
- Test: `src/test/java/com/trova/backend/controller/AdminDashboardControllerTest.java`

**Interfaces:**
- Produces: `AdminGuard.requireAdmin(Authentication) : User`, `DashboardPeriod.of(int days) : DashboardPeriod`(`from()`, `to()`, `since() : LocalDateTime`, `days()`), `DailyCount(LocalDate date, long count)`, `DailySeries.fill(DashboardPeriod, List<DailyCount>) : List<DailyCount>`, `DashboardQueries.dailyCounts(CountedEntity, LocalDateTime) : List<DailyCount>`, `DashboardQueries.CountedEntity { USERS, SAVED_PLACES, TRIPS, AUTO_DRAFTS }`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.trova.backend.controller;

import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 대시보드 집계 API(#161). 날짜를 정하려고 저장 뒤 created_at을 직접 고친다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"app.admin.users=google:dash-admin", "app.instance-name=test-a"})
class AdminDashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RequestPostProcessor loginAs(String sub) {
        ClientRegistration google = ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .build();
        return oauth2Login().clientRegistration(google).attributes(attrs -> {
            attrs.put("sub", sub);
            attrs.put("name", sub);
            attrs.put("picture", "https://example.com/p.jpg");
        });
    }

    private User admin() {
        return userRepository.findByProviderAndProviderUserId("google", "dash-admin")
                .orElseGet(() -> userRepository.save(new User("google", "dash-admin", "관리자", null)));
    }

    private void setCreatedAt(String table, Long id, LocalDateTime at) {
        jdbcTemplate.update("update " + table + " set created_at = ? where id = ?", at, id);
    }

    @Test
    void 관리자가_아니면_사용_데이터를_볼_수_없다() throws Exception {
        userRepository.save(new User("google", "dash-normal", "일반", null));
        mockMvc.perform(get("/api/admin/dashboard/usage").with(loginAs("dash-normal")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 기간은_7_14_30일만_받는다() throws Exception {
        admin();
        mockMvc.perform(get("/api/admin/dashboard/usage?days=0").with(loginAs("dash-admin"))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/dashboard/usage?days=31").with(loginAs("dash-admin"))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/dashboard/usage?days=abc").with(loginAs("dash-admin"))).andExpect(status().isBadRequest());
    }

    @Test
    void 사용_데이터는_빈_날을_0으로_채운_일별_가입자_수를_준다() throws Exception {
        admin();
        User older = userRepository.save(new User("google", "dash-u1", "가입1", null));
        User today = userRepository.save(new User("google", "dash-u2", "가입2", null));
        setCreatedAt("users", older.getId(), LocalDate.now().minusDays(2).atTime(10, 0));
        setCreatedAt("users", today.getId(), LocalDate.now().atTime(0, 5));
        // 관리자 계정도 오늘 가입으로 잡힌다 — 오늘 2명(가입2 + 관리자), 2일 전 1명.

        mockMvc.perform(get("/api/admin/dashboard/usage?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.newUsers.length()").value(7))
                .andExpect(jsonPath("$.newUsers[6].date").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.newUsers[6].count").value(2))
                .andExpect(jsonPath("$.newUsers[4].count").value(1))
                .andExpect(jsonPath("$.newUsers[5].count").value(0))
                .andExpect(jsonPath("$.trips.length()").value(7));
    }
}
```

`UserRepository.findByProviderAndProviderUserId`가 없으면 Step 3에서 `Optional<User> findByProviderAndProviderUserId(String provider, String providerUserId);`를 추가한다(먼저 `grep -n "findByProvider" src/main/java/com/trova/backend/repository/UserRepository.java`로 확인).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*AdminDashboardControllerTest' -q`
Expected: FAIL — `/api/admin/dashboard/usage` 매핑이 없어 404(또는 컴파일 오류).

- [ ] **Step 3: 구현**

`admin/AdminGuard.java`
```java
package com.trova.backend.admin;

import com.trova.backend.entity.User;
import com.trova.backend.service.AdminAccessService;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 관리자 API 공통 권한 확인. 로그인하지 않은 요청은 보안 필터가 먼저 401로 막는다. */
@Service
public class AdminGuard {

    private final CurrentUserService currentUserService;
    private final AdminAccessService adminAccessService;

    public AdminGuard(CurrentUserService currentUserService, AdminAccessService adminAccessService) {
        this.currentUserService = currentUserService;
        this.adminAccessService = adminAccessService;
    }

    public User requireAdmin(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        if (!adminAccessService.isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return user;
    }
}
```

`admin/DashboardPeriod.java`
```java
package com.trova.backend.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/** 대시보드 기간 — 오늘을 포함한 최근 N일(7·14·30). 날짜는 서버 기본 시간대 기준. */
public record DashboardPeriod(int days, LocalDate from, LocalDate to) {

    private static final Set<Integer> ALLOWED = Set.of(7, 14, 30);

    public static DashboardPeriod of(int days) {
        if (!ALLOWED.contains(days)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days는 7, 14, 30 중 하나여야 합니다");
        }
        LocalDate to = LocalDate.now();
        return new DashboardPeriod(days, to.minusDays(days - 1L), to);
    }

    public LocalDateTime since() {
        return from.atStartOfDay();
    }
}
```

`admin/DailyCount.java`
```java
package com.trova.backend.admin;

import java.time.LocalDate;

public record DailyCount(LocalDate date, long count) {
}
```

`admin/DailySeries.java`
```java
package com.trova.backend.admin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** DB가 돌려준 "건수가 있는 날"만의 목록을 기간 전체로 펼치고 빈 날은 0으로 채운다. */
public final class DailySeries {

    private DailySeries() {
    }

    public static List<DailyCount> fill(DashboardPeriod period, List<DailyCount> rows) {
        Map<LocalDate, Long> byDate = rows.stream()
                .collect(Collectors.toMap(DailyCount::date, DailyCount::count, Long::sum));
        List<DailyCount> filled = new ArrayList<>();
        for (LocalDate d = period.from(); !d.isAfter(period.to()); d = d.plusDays(1)) {
            filled.add(new DailyCount(d, byDate.getOrDefault(d, 0L)));
        }
        return filled;
    }
}
```

`admin/DashboardQueries.java`
```java
package com.trova.backend.admin;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 대시보드 일별 건수 쿼리. 엔티티 이름은 아래 enum의 고정 값만 쓰므로 사용자 입력이 쿼리에 들어가지 않는다.
 * 날짜별 묶기는 DB가 한다(응답·메모리가 행 수와 상관없이 일정).
 */
@Repository
public class DashboardQueries {

    public enum CountedEntity {
        USERS("User", ""),
        SAVED_PLACES("SavedPlace", ""),
        TRIPS("Trip", ""),
        AUTO_DRAFTS("TripDraft", " and e.autoCreated = true");

        private final String entityName;
        private final String extraWhere;

        CountedEntity(String entityName, String extraWhere) {
            this.entityName = entityName;
            this.extraWhere = extraWhere;
        }
    }

    private final EntityManager entityManager;

    public DashboardQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public List<DailyCount> dailyCounts(CountedEntity entity, LocalDateTime since) {
        String jpql = "select new com.trova.backend.admin.DailyCount(cast(e.createdAt as LocalDate), count(e)) from "
                + entity.entityName + " e where e.createdAt >= :since" + entity.extraWhere
                + " group by cast(e.createdAt as LocalDate)";
        return entityManager.createQuery(jpql, DailyCount.class).setParameter("since", since).getResultList();
    }

    public long countAll(CountedEntity entity) {
        return entityManager.createQuery("select count(e) from " + entity.entityName + " e" , Long.class).getSingleResult();
    }
}
```

`admin/UsageDashboardService.java`
```java
package com.trova.backend.admin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class UsageDashboardService {

    public record UsageDashboard(int days, long totalUsers, List<DailyCount> newUsers, List<DailyCount> savedPlaces,
                                 List<DailyCount> trips, List<DailyCount> autoDrafts) {
    }

    private final DashboardQueries queries;

    public UsageDashboardService(DashboardQueries queries) {
        this.queries = queries;
    }

    @Transactional(readOnly = true)
    public UsageDashboard usage(int days) {
        DashboardPeriod period = DashboardPeriod.of(days);
        LocalDateTime since = period.since();
        return new UsageDashboard(days, queries.countAll(DashboardQueries.CountedEntity.USERS),
                DailySeries.fill(period, queries.dailyCounts(DashboardQueries.CountedEntity.USERS, since)),
                DailySeries.fill(period, queries.dailyCounts(DashboardQueries.CountedEntity.SAVED_PLACES, since)),
                DailySeries.fill(period, queries.dailyCounts(DashboardQueries.CountedEntity.TRIPS, since)),
                DailySeries.fill(period, queries.dailyCounts(DashboardQueries.CountedEntity.AUTO_DRAFTS, since)));
    }
}
```

`controller/AdminDashboardController.java`
```java
package com.trova.backend.controller;

import com.trova.backend.admin.AdminGuard;
import com.trova.backend.admin.UsageDashboardService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 운영 대시보드 집계 API(#161, 설계 2026-10-11-admin-dashboard-design.md). 관리자만. */
@RestController
public class AdminDashboardController {

    private final AdminGuard adminGuard;
    private final UsageDashboardService usageDashboardService;

    public AdminDashboardController(AdminGuard adminGuard, UsageDashboardService usageDashboardService) {
        this.adminGuard = adminGuard;
        this.usageDashboardService = usageDashboardService;
    }

    @GetMapping("/api/admin/dashboard/usage")
    public UsageDashboardService.UsageDashboard usage(Authentication authentication,
                                                      @RequestParam(defaultValue = "14") int days) {
        adminGuard.requireAdmin(authentication);
        return usageDashboardService.usage(days);
    }
}
```

`days=abc`는 Spring이 `MethodArgumentTypeMismatchException`으로 400을 낸다. 테스트가 500을 받으면 `GlobalExceptionHandler`(있으면)가 이를 500으로 바꾸는지 확인하고, 그 핸들러에 `MethodArgumentTypeMismatchException → 400`을 추가한다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests '*AdminDashboardControllerTest' -q`
Expected: PASS(3개). `cast(e.createdAt as LocalDate)`가 H2에서 오류면 `cast(e.createdAt as date)`로 바꾸고 다시 실행(둘 다 Hibernate 6 HQL 표준 형식).

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/trova/backend/admin src/main/java/com/trova/backend/controller/AdminDashboardController.java src/test/java/com/trova/backend/controller/AdminDashboardControllerTest.java src/main/java/com/trova/backend/repository/UserRepository.java
git commit -m "feat: 관리자 대시보드 사용 데이터 집계 API"
```

---

### Task 2: 분석 파이프라인 탭

**Files:**
- Create: `admin/FailureReason.java`, `admin/Percentiles.java`, `admin/PipelineDashboardService.java`
- Modify: `controller/AdminDashboardController.java`(엔드포인트 추가)
- Test: `src/test/java/com/trova/backend/admin/FailureReasonTest.java`, `admin/PercentilesTest.java`, `controller/AdminDashboardControllerTest.java`(케이스 추가)

**Interfaces:**
- Consumes: `DashboardPeriod`, `DailySeries`, `AdminGuard`
- Produces: `FailureReason.classify(String) : FailureReason`(`NO_PLACES, AI_QUOTA, SOURCE_RATE_LIMITED, STALE, OTHER`), `Percentiles.of(List<Long> values, double p) : Long`(빈 목록이면 `null`, nearest-rank), `PipelineDashboardService.pipeline(int days)`, `PipelineDashboardService.stuckJobs() : List<StuckJob>`, `record StuckJob(Long id, String status, String stage, long minutesSinceUpdate)`

- [ ] **Step 1: 실패하는 테스트 작성**

`admin/FailureReasonTest.java`
```java
package com.trova.backend.admin;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.service.OrphanedJobRecoveryService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FailureReasonTest {

    @Test
    void 알려진_실패_문구는_이유별로_나눈다() {
        assertThat(FailureReason.classify(ProcessingJob.NO_PLACES_MESSAGE)).isEqualTo(FailureReason.NO_PLACES);
        assertThat(FailureReason.classify(ProcessingJob.AI_QUOTA_MESSAGE)).isEqualTo(FailureReason.AI_QUOTA);
        assertThat(FailureReason.classify(ProcessingJob.SOURCE_RATE_LIMIT_MESSAGE)).isEqualTo(FailureReason.SOURCE_RATE_LIMITED);
        assertThat(FailureReason.classify(OrphanedJobRecoveryService.STALE_MESSAGE)).isEqualTo(FailureReason.STALE);
    }

    @Test
    void 모르는_문구나_비어_있으면_기타다() {
        assertThat(FailureReason.classify("yt-dlp 다운로드 실패")).isEqualTo(FailureReason.OTHER);
        assertThat(FailureReason.classify(null)).isEqualTo(FailureReason.OTHER);
        assertThat(FailureReason.classify(" ")).isEqualTo(FailureReason.OTHER);
    }
}
```

`admin/PercentilesTest.java`
```java
package com.trova.backend.admin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PercentilesTest {

    @Test
    void 가장_가까운_순위_방식으로_백분위를_구한다() {
        List<Long> values = List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L);
        assertThat(Percentiles.of(values, 0.5)).isEqualTo(50L);
        assertThat(Percentiles.of(values, 0.9)).isEqualTo(90L);
        assertThat(Percentiles.of(List.of(7L), 0.9)).isEqualTo(7L);
    }

    @Test
    void 값이_없으면_null이다() {
        assertThat(Percentiles.of(List.of(), 0.5)).isNull();
    }
}
```

`AdminDashboardControllerTest`에 추가(필드 `@Autowired private ProcessingJobRepository processingJobRepository;`, import `com.trova.backend.entity.ProcessingJob`, `com.trova.backend.entity.SourcePlatform`, `com.trova.backend.repository.ProcessingJobRepository`):
```java
    private ProcessingJob job(User user, String url, String status, String error, LocalDateTime createdAt, LocalDateTime updatedAt) {
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(user, url, SourcePlatform.YOUTUBE));
        jdbcTemplate.update("update processing_jobs set status = ?, error_message = ?, created_at = ?, updated_at = ? where id = ?",
                status, error, createdAt, updatedAt, job.getId());
        return job;
    }

    @Test
    void 파이프라인은_일별_성공_실패와_처리_시간_실패_이유를_준다() throws Exception {
        User admin = admin();
        LocalDateTime today = LocalDate.now().atTime(0, 10);
        job(admin, "https://youtu.be/dashA1", "DONE", null, today, today.plusSeconds(20));
        job(admin, "https://youtu.be/dashA2", "DONE", null, today, today.plusSeconds(40));
        job(admin, "https://youtu.be/dashA3", "FAILED", ProcessingJob.SOURCE_RATE_LIMIT_MESSAGE, today, today.plusSeconds(5));
        job(admin, "https://youtu.be/dashA4", "FAILED", null, today, today.plusSeconds(5));

        mockMvc.perform(get("/api/admin/dashboard/pipeline?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily.length()").value(7))
                .andExpect(jsonPath("$.daily[6].total").value(4))
                .andExpect(jsonPath("$.daily[6].done").value(2))
                .andExpect(jsonPath("$.daily[6].failed").value(2))
                .andExpect(jsonPath("$.durationP50Seconds").value(20))
                .andExpect(jsonPath("$.durationP90Seconds").value(40))
                .andExpect(jsonPath("$.failureReasons[?(@.reason == 'SOURCE_RATE_LIMITED')].count").value(1))
                .andExpect(jsonPath("$.failureReasons[?(@.reason == 'OTHER')].count").value(1));
    }

    @Test
    void 오래_갱신되지_않은_진행_중_작업은_멈춘_작업으로_보여주되_개인정보는_빼고_준다() throws Exception {
        User admin = admin();
        LocalDateTime old = LocalDateTime.now().minusMinutes(9);
        ProcessingJob stuck = job(admin, "https://youtu.be/dashStuck", "PROCESSING", null, old, old);
        job(admin, "https://youtu.be/dashFresh", "PROCESSING", null, LocalDateTime.now(), LocalDateTime.now());

        mockMvc.perform(get("/api/admin/dashboard/pipeline?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stuckJobs.length()").value(1))
                .andExpect(jsonPath("$.stuckJobs[0].id").value(stuck.getId()))
                .andExpect(jsonPath("$.stuckJobs[0].sourceUrl").doesNotExist());
    }

    @Test
    void 작업이_없으면_파이프라인은_0과_빈_목록을_준다() throws Exception {
        admin();
        mockMvc.perform(get("/api/admin/dashboard/pipeline?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily.length()").value(7))
                .andExpect(jsonPath("$.durationP90Seconds").doesNotExist());
    }
```

주의: 마지막 테스트는 같은 DB에 다른 테스트 클래스가 남긴 작업이 없어야 한다 — `@Transactional`로 각 테스트가 롤백되므로 다른 클래스의 커밋된 데이터만 문제. 실패하면 `daily.length()`만 남기고 `durationP90Seconds` 단언을 지운다(그 사실을 커밋 메시지가 아니라 테스트 주석에 적는다).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*FailureReasonTest' --tests '*PercentilesTest' --tests '*AdminDashboardControllerTest' -q`
Expected: FAIL — 클래스 없음/매핑 없음.

- [ ] **Step 3: 구현**

`admin/FailureReason.java`
```java
package com.trova.backend.admin;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.service.OrphanedJobRecoveryService;

/** 처리 작업 실패 이유 — 실패 시 저장하는 문구(상수)로 나눈다. 새 실패 문구가 생기면 여기에 더한다. */
public enum FailureReason {
    NO_PLACES, AI_QUOTA, SOURCE_RATE_LIMITED, STALE, OTHER;

    public static FailureReason classify(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return OTHER;
        }
        if (errorMessage.equals(ProcessingJob.NO_PLACES_MESSAGE)) {
            return NO_PLACES;
        }
        if (errorMessage.equals(ProcessingJob.AI_QUOTA_MESSAGE)) {
            return AI_QUOTA;
        }
        if (errorMessage.equals(ProcessingJob.SOURCE_RATE_LIMIT_MESSAGE)) {
            return SOURCE_RATE_LIMITED;
        }
        if (errorMessage.equals(OrphanedJobRecoveryService.STALE_MESSAGE)) {
            return STALE;
        }
        return OTHER;
    }
}
```

`admin/Percentiles.java`
```java
package com.trova.backend.admin;

import java.util.List;

public final class Percentiles {

    private Percentiles() {
    }

    /** nearest-rank 백분위(p는 0~1). 값이 없으면 null. */
    public static Long of(List<Long> values, double p) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(p * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }
}
```

`admin/PipelineDashboardService.java`
```java
package com.trova.backend.admin;

import com.trova.backend.entity.JobStatus;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 분석 파이프라인 탭. 처리 시간 백분위는 DB마다 함수가 달라(H2·Postgres) 기간 안 작업의 필요한 열만 읽어 Java에서 계산한다
 * (30일 행이 10만을 넘으면 DB 집계로 바꾼다).
 */
@Service
public class PipelineDashboardService {

    public record DailyJobStats(LocalDate date, long total, long done, long failed) {
    }

    public record ReasonCount(String reason, long count) {
    }

    public record StuckJob(Long id, String status, String stage, long minutesSinceUpdate) {
    }

    public record PipelineDashboard(int days, List<DailyJobStats> daily, Long durationP50Seconds,
                                    Long durationP90Seconds, List<ReasonCount> failureReasons, List<StuckJob> stuckJobs) {
    }

    public record JobRow(LocalDateTime createdAt, LocalDateTime updatedAt, JobStatus status, String errorMessage) {
    }

    private final EntityManager entityManager;
    private final int stuckAfterMinutes;

    public PipelineDashboardService(EntityManager entityManager,
                                    @Value("${app.admin.dashboard.stuck-after-minutes:5}") int stuckAfterMinutes) {
        this.entityManager = entityManager;
        this.stuckAfterMinutes = stuckAfterMinutes;
    }

    @Transactional(readOnly = true)
    public PipelineDashboard pipeline(int days) {
        DashboardPeriod period = DashboardPeriod.of(days);
        List<JobRow> rows = entityManager.createQuery(
                        "select new com.trova.backend.admin.PipelineDashboardService.JobRow(j.createdAt, j.updatedAt, j.status, j.errorMessage) "
                                + "from ProcessingJob j where j.createdAt >= :since", JobRow.class)
                .setParameter("since", period.since())
                .getResultList();

        Map<LocalDate, List<JobRow>> byDate = rows.stream().collect(Collectors.groupingBy(r -> r.createdAt().toLocalDate()));
        List<DailyJobStats> daily = new ArrayList<>();
        for (LocalDate d = period.from(); !d.isAfter(period.to()); d = d.plusDays(1)) {
            List<JobRow> dayRows = byDate.getOrDefault(d, List.of());
            daily.add(new DailyJobStats(d, dayRows.size(),
                    dayRows.stream().filter(r -> r.status() == JobStatus.DONE).count(),
                    dayRows.stream().filter(r -> r.status() == JobStatus.FAILED).count()));
        }

        List<Long> durations = rows.stream()
                .filter(r -> r.status() == JobStatus.DONE)
                .map(r -> Duration.between(r.createdAt(), r.updatedAt()).toSeconds())
                .toList();

        Map<FailureReason, Long> reasons = rows.stream()
                .filter(r -> r.status() == JobStatus.FAILED)
                .collect(Collectors.groupingBy(r -> FailureReason.classify(r.errorMessage()), Collectors.counting()));
        List<ReasonCount> failureReasons = Arrays.stream(FailureReason.values())
                .filter(reasons::containsKey)
                .map(reason -> new ReasonCount(reason.name(), reasons.get(reason)))
                .toList();

        return new PipelineDashboard(days, daily, Percentiles.of(durations, 0.5), Percentiles.of(durations, 0.9),
                failureReasons, stuckJobs());
    }

    /** 진행 중인데 stuck-after-minutes 넘게 갱신이 없는 작업(최대 20개, 오래된 순). 15분이 지나면 OrphanedJobRecoveryService가 FAILED로 바꾼다. */
    @Transactional(readOnly = true)
    public List<StuckJob> stuckJobs() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.minusMinutes(stuckAfterMinutes);
        return entityManager.createQuery(
                        "select j.id, j.status, j.currentStage, j.updatedAt from ProcessingJob j "
                                + "where j.status in :inFlight and j.updatedAt < :threshold order by j.updatedAt", Object[].class)
                .setParameter("inFlight", List.of(JobStatus.PENDING, JobStatus.PROCESSING))
                .setParameter("threshold", threshold)
                .setMaxResults(20)
                .getResultList()
                .stream()
                .map(r -> new StuckJob((Long) r[0], String.valueOf(r[1]), r[2] == null ? null : String.valueOf(r[2]),
                        Duration.between((LocalDateTime) r[3], now).toMinutes()))
                .toList();
    }
}
```

HQL에서 중첩 record 생성자 이름은 `$` 구분이 필요할 수 있다 — 실패하면 `"com.trova.backend.admin.PipelineDashboardService$JobRow"`로 바꾼다.

`AdminDashboardController`에 필드·생성자 인자 `PipelineDashboardService pipelineDashboardService`와 메서드 추가:
```java
    @GetMapping("/api/admin/dashboard/pipeline")
    public PipelineDashboardService.PipelineDashboard pipeline(Authentication authentication,
                                                               @RequestParam(defaultValue = "14") int days) {
        adminGuard.requireAdmin(authentication);
        return pipelineDashboardService.pipeline(days);
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests '*FailureReasonTest' --tests '*PercentilesTest' --tests '*AdminDashboardControllerTest' -q`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/trova/backend/admin src/main/java/com/trova/backend/controller/AdminDashboardController.java src/test/java/com/trova/backend
git commit -m "feat: 관리자 대시보드 분석 파이프라인 집계"
```

---

### Task 3: AI 한도·호출 탭

**Files:**
- Create: `admin/AiDashboardService.java`
- Modify: `controller/AdminDashboardController.java`
- Test: `controller/AdminDashboardControllerTest.java`(케이스 추가)

**Interfaces:**
- Consumes: `DashboardPeriod`
- Produces: `AiDashboardService.ai(int days) : AiDashboard`, `AiDashboardService.geminiCallsSince(LocalDateTime) : long`

- [ ] **Step 1: 실패하는 테스트 작성**

`AdminDashboardControllerTest`에 추가(필드 `@Autowired private ApiCallLogRepository apiCallLogRepository;`, import `com.trova.backend.entity.ApiCallLog`, `com.trova.backend.repository.ApiCallLogRepository`). `ApiCallLog` 생성자 인자 순서는 `ConversationService.java:60`의 호출(`provider, operation, jobId, latencyMs, success, errorMessage, promptTokens, responseTokens, totalTokens`)을 따른다 — 먼저 `sed -n 50,70p src/main/java/com/trova/backend/entity/ApiCallLog.java`로 확인.
```java
    @Test
    void AI_탭은_일별_호출_실패_토큰과_작업당_호출_수를_준다() throws Exception {
        admin();
        apiCallLogRepository.save(new ApiCallLog("gemini", "extract_places", 101L, 900L, true, null, 100, 50, 150));
        apiCallLogRepository.save(new ApiCallLog("gemini", "extract_places", 101L, 800L, false, "429", null, null, null));
        apiCallLogRepository.save(new ApiCallLog("kakao", "address_search", 102L, 60L, true, null, null, null, null));
        apiCallLogRepository.save(new ApiCallLog("gemini", "conversation-turn", null, 500L, true, null, 10, 5, 15));

        mockMvc.perform(get("/api/admin/dashboard/ai?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily.length()").value(7))
                .andExpect(jsonPath("$.daily[6].calls").value(4))
                .andExpect(jsonPath("$.daily[6].failures").value(1))
                .andExpect(jsonPath("$.daily[6].tokens").value(165))
                .andExpect(jsonPath("$.callsPerJob").value(1.5))
                .andExpect(jsonPath("$.byOperation[0].operation").value("extract_places"))
                .andExpect(jsonPath("$.byOperation[0].calls").value(2));
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*AdminDashboardControllerTest' -q`
Expected: FAIL — `/api/admin/dashboard/ai` 404.

- [ ] **Step 3: 구현**

`admin/AiDashboardService.java`
```java
package com.trova.backend.admin;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** AI·외부 API 호출 탭(api_call_logs). 이 서버가 남긴 호출만 — 로컬 서버가 다른 DB에 쓴 호출은 세지 않는다. */
@Service
public class AiDashboardService {

    public record DailyAiStats(LocalDate date, long calls, long failures, long tokens) {
    }

    public record OperationCount(String provider, String operation, long calls) {
    }

    public record AiDashboard(int days, List<DailyAiStats> daily, List<OperationCount> byOperation, Double callsPerJob) {
    }

    public record CallRow(LocalDateTime createdAt, String provider, String operation, boolean success,
                          Integer totalTokens, Long jobId) {
    }

    private final EntityManager entityManager;

    public AiDashboardService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public AiDashboard ai(int days) {
        DashboardPeriod period = DashboardPeriod.of(days);
        List<CallRow> rows = entityManager.createQuery(
                        "select new com.trova.backend.admin.AiDashboardService.CallRow(c.createdAt, c.provider, c.operation, "
                                + "c.success, c.totalTokens, c.jobId) from ApiCallLog c where c.createdAt >= :since", CallRow.class)
                .setParameter("since", period.since())
                .getResultList();

        Map<LocalDate, List<CallRow>> byDate = rows.stream().collect(Collectors.groupingBy(r -> r.createdAt().toLocalDate()));
        List<DailyAiStats> daily = new ArrayList<>();
        for (LocalDate d = period.from(); !d.isAfter(period.to()); d = d.plusDays(1)) {
            List<CallRow> dayRows = byDate.getOrDefault(d, List.of());
            daily.add(new DailyAiStats(d, dayRows.size(),
                    dayRows.stream().filter(r -> !r.success()).count(),
                    dayRows.stream().map(CallRow::totalTokens).filter(Objects::nonNull).mapToLong(Integer::longValue).sum()));
        }

        List<OperationCount> byOperation = rows.stream()
                .collect(Collectors.groupingBy(r -> r.provider() + "|" + r.operation(), Collectors.counting()))
                .entrySet().stream()
                .map(e -> {
                    String[] key = e.getKey().split("\\|", 2);
                    return new OperationCount(key[0], key[1], e.getValue());
                })
                .sorted(Comparator.comparingLong(OperationCount::calls).reversed())
                .toList();

        List<CallRow> withJob = rows.stream().filter(r -> r.jobId() != null).toList();
        long jobs = withJob.stream().map(CallRow::jobId).distinct().count();
        Double callsPerJob = jobs == 0 ? null : (double) withJob.size() / jobs;

        return new AiDashboard(days, daily, byOperation, callsPerJob);
    }

    @Transactional(readOnly = true)
    public long geminiCallsSince(LocalDateTime since) {
        return entityManager.createQuery(
                        "select count(c) from ApiCallLog c where c.provider = 'gemini' and c.createdAt >= :since", Long.class)
                .setParameter("since", since)
                .getSingleResult();
    }
}
```

(중첩 record 이름은 Task 2와 같이 실패하면 `$` 구분으로.)

`AdminDashboardController`에 `AiDashboardService aiDashboardService` 주입과:
```java
    @GetMapping("/api/admin/dashboard/ai")
    public AiDashboardService.AiDashboard ai(Authentication authentication, @RequestParam(defaultValue = "14") int days) {
        adminGuard.requireAdmin(authentication);
        return aiDashboardService.ai(days);
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests '*AdminDashboardControllerTest' -q`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/trova/backend/admin/AiDashboardService.java src/main/java/com/trova/backend/controller/AdminDashboardController.java src/test/java/com/trova/backend/controller/AdminDashboardControllerTest.java
git commit -m "feat: 관리자 대시보드 AI 호출 집계"
```

---

### Task 4: 서버 상태 스냅샷과 서버 탭

**Files:**
- Create: `entity/ServerMetricSnapshot.java`, `repository/ServerMetricSnapshotRepository.java`, `admin/RequestTimingFilter.java`, `admin/ServerMetricSnapshotRecorder.java`, `admin/ServerDashboardService.java`
- Modify: `controller/AdminDashboardController.java`
- Test: `src/test/java/com/trova/backend/admin/ServerMetricSnapshotRecorderTest.java`, `controller/AdminDashboardControllerTest.java`(케이스 추가)

**Interfaces:**
- Consumes: `Percentiles.of`, `DashboardPeriod`
- Produces: `RequestTimingFilter.drain() : List<RequestTimingFilter.Sample>`(`record Sample(long durationMs, int status)`), `ServerMetricSnapshotRecorder.record()`, `ServerMetricSnapshotRecorder.purgeOld()`, `ServerDashboardService.server(int days)`, `ServerDashboardService.maxP90Since(LocalDateTime) : Long`, `ServerMetricSnapshotRepository.findByInstanceNameAndMeasuredAtGreaterThanEqualOrderByMeasuredAt(String, LocalDateTime)`

- [ ] **Step 1: 실패하는 테스트 작성**

`admin/ServerMetricSnapshotRecorderTest.java`
```java
package com.trova.backend.admin;

import com.trova.backend.entity.ServerMetricSnapshot;
import com.trova.backend.repository.ServerMetricSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "app.instance-name=test-rec")
class ServerMetricSnapshotRecorderTest {

    @Autowired
    private ServerMetricSnapshotRecorder recorder;

    @Autowired
    private RequestTimingFilter timingFilter;

    @Autowired
    private ServerMetricSnapshotRepository repository;

    @Test
    void 모인_요청으로_5분_스냅샷을_만들고_모은_값은_비운다() {
        timingFilter.drain();
        for (long ms = 10; ms <= 100; ms += 10) {
            timingFilter.add(new RequestTimingFilter.Sample(ms, ms == 100 ? 500 : 200));
        }

        recorder.record();

        List<ServerMetricSnapshot> saved = repository.findByInstanceNameAndMeasuredAtGreaterThanEqualOrderByMeasuredAt(
                "test-rec", LocalDateTime.now().minusMinutes(1));
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getRequestCount()).isEqualTo(10);
        assertThat(saved.get(0).getP50Ms()).isEqualTo(50);
        assertThat(saved.get(0).getP90Ms()).isEqualTo(90);
        assertThat(saved.get(0).getServerErrorCount()).isEqualTo(1);
        assertThat(saved.get(0).getHeapUsedMb()).isPositive();
        assertThat(timingFilter.drain()).isEmpty();
    }

    @Test
    void 요청이_없던_5분도_요청_0으로_남긴다() {
        timingFilter.drain();
        recorder.record();
        List<ServerMetricSnapshot> saved = repository.findByInstanceNameAndMeasuredAtGreaterThanEqualOrderByMeasuredAt(
                "test-rec", LocalDateTime.now().minusMinutes(1));
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getRequestCount()).isZero();
        assertThat(saved.get(0).getP90Ms()).isNull();
    }

    @Test
    void 30일_지난_스냅샷은_지운다() {
        repository.save(new ServerMetricSnapshot("test-rec", LocalDateTime.now().minusDays(31), 1, 10, 10, 0, 100, 1));
        repository.save(new ServerMetricSnapshot("test-rec", LocalDateTime.now().minusDays(29), 1, 10, 10, 0, 100, 1));

        recorder.purgeOld();

        assertThat(repository.findByInstanceNameAndMeasuredAtGreaterThanEqualOrderByMeasuredAt(
                "test-rec", LocalDateTime.now().minusDays(60))).hasSize(1);
    }
}
```

`AdminDashboardControllerTest`에 추가(필드 `@Autowired private ServerMetricSnapshotRepository snapshotRepository;`, import `com.trova.backend.entity.ServerMetricSnapshot`, `com.trova.backend.repository.ServerMetricSnapshotRepository`):
```java
    @Test
    void 서버_탭은_이_서버_이름의_스냅샷만_준다() throws Exception {
        admin();
        snapshotRepository.save(new ServerMetricSnapshot("test-a", LocalDateTime.now().minusMinutes(10), 12, 30, 80, 0, 200, 2));
        snapshotRepository.save(new ServerMetricSnapshot("other-local", LocalDateTime.now().minusMinutes(10), 99, 1, 1, 0, 1, 1));

        mockMvc.perform(get("/api/admin/dashboard/server?days=7").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceName").value("test-a"))
                .andExpect(jsonPath("$.snapshots.length()").value(1))
                .andExpect(jsonPath("$.snapshots[0].requestCount").value(12))
                .andExpect(jsonPath("$.snapshots[0].p90Ms").value(80));
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*ServerMetricSnapshotRecorderTest' --tests '*AdminDashboardControllerTest' -q`
Expected: FAIL — 클래스 없음.

- [ ] **Step 3: 구현**

`entity/ServerMetricSnapshot.java`
```java
package com.trova.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 서버 상태 5분 스냅샷(#161). 운영·로컬 서버가 같은 DB를 쓸 수 있어 서버 이름(instanceName)을 함께 남긴다. 30일 보관. */
@Entity
@Table(name = "server_metric_snapshots", indexes = @Index(name = "idx_server_metric_instance_time", columnList = "instance_name, measured_at"))
public class ServerMetricSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_name", nullable = false, length = 40)
    private String instanceName;

    @Column(name = "measured_at", nullable = false)
    private LocalDateTime measuredAt;

    @Column(name = "request_count", nullable = false)
    private int requestCount;

    @Column(name = "p50_ms")
    private Integer p50Ms;

    @Column(name = "p90_ms")
    private Integer p90Ms;

    @Column(name = "server_error_count", nullable = false)
    private int serverErrorCount;

    @Column(name = "heap_used_mb", nullable = false)
    private int heapUsedMb;

    @Column(name = "db_active_connections")
    private Integer dbActiveConnections;

    protected ServerMetricSnapshot() {
    }

    public ServerMetricSnapshot(String instanceName, LocalDateTime measuredAt, int requestCount, Integer p50Ms,
                                Integer p90Ms, int serverErrorCount, int heapUsedMb, Integer dbActiveConnections) {
        this.instanceName = instanceName;
        this.measuredAt = measuredAt;
        this.requestCount = requestCount;
        this.p50Ms = p50Ms;
        this.p90Ms = p90Ms;
        this.serverErrorCount = serverErrorCount;
        this.heapUsedMb = heapUsedMb;
        this.dbActiveConnections = dbActiveConnections;
    }

    public Long getId() { return id; }
    public String getInstanceName() { return instanceName; }
    public LocalDateTime getMeasuredAt() { return measuredAt; }
    public int getRequestCount() { return requestCount; }
    public Integer getP50Ms() { return p50Ms; }
    public Integer getP90Ms() { return p90Ms; }
    public int getServerErrorCount() { return serverErrorCount; }
    public int getHeapUsedMb() { return heapUsedMb; }
    public Integer getDbActiveConnections() { return dbActiveConnections; }
}
```

(게터 한 줄 형식은 `ProcessingJob.java:107`처럼 이 레포에 이미 있는 형식이다.)

`repository/ServerMetricSnapshotRepository.java`
```java
package com.trova.backend.repository;

import com.trova.backend.entity.ServerMetricSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ServerMetricSnapshotRepository extends JpaRepository<ServerMetricSnapshot, Long> {
    List<ServerMetricSnapshot> findByInstanceNameAndMeasuredAtGreaterThanEqualOrderByMeasuredAt(String instanceName, LocalDateTime from);

    @Modifying
    @Query("delete from ServerMetricSnapshot s where s.measuredAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
```

`admin/RequestTimingFilter.java`
```java
package com.trova.backend.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * /api/** 요청의 처리 시간·상태를 메모리에 모았다가 5분 스냅샷 때 가져간다(ServerMetricSnapshotRecorder).
 * Micrometer 히스토그램을 켜는 대신 직접 모으는 이유: 운영 서버 메모리가 작아(E2.1.Micro) 지표를 더 늘리지 않고,
 * 5분 단위 정확한 p50·p90만 필요해서. 5분에 5만 건을 넘으면 넘친 만큼은 버린다(감으로 정한 상한, 실측 아님).
 */
@Component
public class RequestTimingFilter extends OncePerRequestFilter {

    public record Sample(long durationMs, int status) {
    }

    private static final int MAX_SAMPLES = 50_000;
    private final List<Sample> samples = new ArrayList<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            add(new Sample((System.nanoTime() - start) / 1_000_000, response.getStatus()));
        }
    }

    public synchronized void add(Sample sample) {
        if (samples.size() < MAX_SAMPLES) {
            samples.add(sample);
        }
    }

    public synchronized List<Sample> drain() {
        List<Sample> drained = List.copyOf(samples);
        samples.clear();
        return drained;
    }
}
```

`admin/ServerMetricSnapshotRecorder.java`
```java
package com.trova.backend.admin;

import com.trova.backend.entity.ServerMetricSnapshot;
import com.trova.backend.repository.ServerMetricSnapshotRepository;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 5분마다 서버 상태 한 줄을 남기고, 하루 한 번 30일 지난 줄을 지운다(#161). */
@Service
public class ServerMetricSnapshotRecorder {

    private final RequestTimingFilter timingFilter;
    private final ServerMetricSnapshotRepository repository;
    private final DataSource dataSource;
    private final String instanceName;

    public ServerMetricSnapshotRecorder(RequestTimingFilter timingFilter, ServerMetricSnapshotRepository repository,
                                        DataSource dataSource, @Value("${app.instance-name:local}") String instanceName) {
        this.timingFilter = timingFilter;
        this.repository = repository;
        this.dataSource = dataSource;
        this.instanceName = instanceName;
    }

    @Scheduled(fixedRate = 5, initialDelay = 5, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void record() {
        List<RequestTimingFilter.Sample> samples = timingFilter.drain();
        List<Long> durations = samples.stream().map(RequestTimingFilter.Sample::durationMs).toList();
        Long p50 = Percentiles.of(durations, 0.5);
        Long p90 = Percentiles.of(durations, 0.9);
        int errors = (int) samples.stream().filter(s -> s.status() >= 500).count();
        Runtime rt = Runtime.getRuntime();
        int heapMb = (int) ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        repository.save(new ServerMetricSnapshot(instanceName, LocalDateTime.now(), samples.size(),
                p50 == null ? null : p50.intValue(), p90 == null ? null : p90.intValue(), errors, heapMb, activeConnections()));
    }

    @Scheduled(cron = "0 30 4 * * *")
    @Transactional
    public void purgeOld() {
        repository.deleteOlderThan(LocalDateTime.now().minusDays(30));
    }

    private Integer activeConnections() {
        if (dataSource instanceof HikariDataSource hikari) {
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            return pool == null ? null : pool.getActiveConnections();
        }
        return null;
    }
}
```

`admin/ServerDashboardService.java`
```java
package com.trova.backend.admin;

import com.trova.backend.entity.ServerMetricSnapshot;
import com.trova.backend.repository.ServerMetricSnapshotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** 서버 상태 탭 — 이 서버(app.instance-name)가 남긴 스냅샷만. 같은 DB를 쓰는 다른 서버 값은 섞지 않는다. */
@Service
public class ServerDashboardService {

    public record SnapshotView(LocalDateTime measuredAt, int requestCount, Integer p50Ms, Integer p90Ms,
                               int serverErrorCount, int heapUsedMb, Integer dbActiveConnections) {
    }

    public record ServerDashboard(int days, String instanceName, List<SnapshotView> snapshots) {
    }

    private final ServerMetricSnapshotRepository repository;
    private final String instanceName;

    public ServerDashboardService(ServerMetricSnapshotRepository repository,
                                  @Value("${app.instance-name:local}") String instanceName) {
        this.repository = repository;
        this.instanceName = instanceName;
    }

    @Transactional(readOnly = true)
    public ServerDashboard server(int days) {
        DashboardPeriod period = DashboardPeriod.of(days);
        List<SnapshotView> views = repository
                .findByInstanceNameAndMeasuredAtGreaterThanEqualOrderByMeasuredAt(instanceName, period.since())
                .stream()
                .map(s -> new SnapshotView(s.getMeasuredAt(), s.getRequestCount(), s.getP50Ms(), s.getP90Ms(),
                        s.getServerErrorCount(), s.getHeapUsedMb(), s.getDbActiveConnections()))
                .toList();
        return new ServerDashboard(days, instanceName, views);
    }

    /** 요약 카드용 — since 이후 스냅샷의 p90 중 가장 큰 값. 스냅샷이 없으면 null. */
    @Transactional(readOnly = true)
    public Long maxP90Since(LocalDateTime since) {
        return repository.findByInstanceNameAndMeasuredAtGreaterThanEqualOrderByMeasuredAt(instanceName, since).stream()
                .map(ServerMetricSnapshot::getP90Ms)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .boxed()
                .max(Long::compare)
                .orElse(null);
    }
}
```

`AdminDashboardController`에 `ServerDashboardService serverDashboardService` 주입과:
```java
    @GetMapping("/api/admin/dashboard/server")
    public ServerDashboardService.ServerDashboard server(Authentication authentication,
                                                         @RequestParam(defaultValue = "14") int days) {
        adminGuard.requireAdmin(authentication);
        return serverDashboardService.server(days);
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests '*ServerMetricSnapshotRecorderTest' --tests '*AdminDashboardControllerTest' -q`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/trova/backend/entity/ServerMetricSnapshot.java src/main/java/com/trova/backend/repository/ServerMetricSnapshotRepository.java src/main/java/com/trova/backend/admin src/main/java/com/trova/backend/controller/AdminDashboardController.java src/test/java/com/trova/backend
git commit -m "feat: 서버 상태 5분 스냅샷과 관리자 대시보드 서버 탭"
```

---

### Task 5: 요약 카드

**Files:**
- Create: `admin/DashboardSummaryService.java`
- Modify: `controller/AdminDashboardController.java`
- Test: `controller/AdminDashboardControllerTest.java`(케이스 추가)

**Interfaces:**
- Consumes: `AiDashboardService.geminiCallsSince`, `PipelineDashboardService.stuckJobs`, `ServerDashboardService.maxP90Since`
- Produces: `DashboardSummaryService.summary() : DashboardSummary`, `record Card(String key, Long value, long threshold, boolean alert, String note)`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
    @Test
    void 요약은_카드_4개를_주고_기준을_넘으면_경고한다() throws Exception {
        User admin = admin();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 6; i++) {
            job(admin, "https://youtu.be/dashF" + i, "FAILED", null, now.minusHours(1), now.minusHours(1));
        }

        mockMvc.perform(get("/api/admin/dashboard/summary").with(loginAs("dash-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(4))
                .andExpect(jsonPath("$.cards[?(@.key == 'failures24h')].value").value(6))
                .andExpect(jsonPath("$.cards[?(@.key == 'failures24h')].alert").value(true))
                .andExpect(jsonPath("$.cards[?(@.key == 'p90LastHour')].alert").value(false));
    }
```

(`p90LastHour`는 이 테스트 인스턴스 이름 `test-a`의 최근 1시간 스냅샷이 없으면 `value` null·경고 아님. 앞 Task 4 테스트는 롤백되므로 남지 않는다.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*AdminDashboardControllerTest' -q`
Expected: FAIL — `/api/admin/dashboard/summary` 404.

- [ ] **Step 3: 구현**

`admin/DashboardSummaryService.java`
```java
package com.trova.backend.admin;

import com.trova.backend.entity.JobStatus;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** 대시보드 맨 위 "지금 확인할 것" 카드 4개(#161). 기준값은 설정, 처음 값은 감으로 정함(실측 아님). */
@Service
public class DashboardSummaryService {

    public record Card(String key, Long value, long threshold, boolean alert, String note) {
    }

    public record DashboardSummary(List<Card> cards) {
    }

    // Gemini 무료 한도는 태평양 시간 자정에 초기화된다(qaflow 사용량 확인과 같은 기준).
    private static final ZoneId GEMINI_RESET_ZONE = ZoneId.of("America/Los_Angeles");

    private final AiDashboardService aiDashboardService;
    private final PipelineDashboardService pipelineDashboardService;
    private final ServerDashboardService serverDashboardService;
    private final EntityManager entityManager;
    private final long geminiAlert;
    private final long geminiDailyLimit;
    private final long failuresAlert;
    private final long p90AlertMs;

    public DashboardSummaryService(AiDashboardService aiDashboardService, PipelineDashboardService pipelineDashboardService,
                                   ServerDashboardService serverDashboardService, EntityManager entityManager,
                                   @Value("${app.admin.dashboard.gemini-alert:800}") long geminiAlert,
                                   @Value("${app.admin.dashboard.gemini-daily-limit:1000}") long geminiDailyLimit,
                                   @Value("${app.admin.dashboard.failures-alert:5}") long failuresAlert,
                                   @Value("${app.admin.dashboard.p90-alert-ms:1000}") long p90AlertMs) {
        this.aiDashboardService = aiDashboardService;
        this.pipelineDashboardService = pipelineDashboardService;
        this.serverDashboardService = serverDashboardService;
        this.entityManager = entityManager;
        this.geminiAlert = geminiAlert;
        this.geminiDailyLimit = geminiDailyLimit;
        this.failuresAlert = failuresAlert;
        this.p90AlertMs = p90AlertMs;
    }

    @Transactional(readOnly = true)
    public DashboardSummary summary() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime geminiDayStart = LocalDate.now(GEMINI_RESET_ZONE).atStartOfDay(GEMINI_RESET_ZONE)
                .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        long gemini = aiDashboardService.geminiCallsSince(geminiDayStart);
        long failures = entityManager.createQuery(
                        "select count(j) from ProcessingJob j where j.status = :failed and j.updatedAt >= :since", Long.class)
                .setParameter("failed", JobStatus.FAILED)
                .setParameter("since", now.minusHours(24))
                .getSingleResult();
        long stuck = pipelineDashboardService.stuckJobs().size();
        Long p90 = serverDashboardService.maxP90Since(now.minusHours(1));

        return new DashboardSummary(List.of(
                card("geminiToday", gemini, geminiAlert, "이 서버 호출만 · 하루 한도 " + geminiDailyLimit + "회(태평양 자정 기준)"),
                card("failures24h", failures, failuresAlert, "최근 24시간 분석 실패"),
                card("stuckJobs", stuck, 1, "진행 중인데 오래 갱신이 없는 작업"),
                card("p90LastHour", p90, p90AlertMs, "최근 1시간 응답 시간 p90(ms), 스냅샷 기준")));
    }

    private Card card(String key, Long value, long threshold, String note) {
        return new Card(key, value, threshold, value != null && value >= threshold, note);
    }
}
```

`AdminDashboardController`에 `DashboardSummaryService dashboardSummaryService` 주입과:
```java
    @GetMapping("/api/admin/dashboard/summary")
    public DashboardSummaryService.DashboardSummary summary(Authentication authentication) {
        adminGuard.requireAdmin(authentication);
        return dashboardSummaryService.summary();
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests '*AdminDashboardControllerTest' -q`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/trova/backend/admin/DashboardSummaryService.java src/main/java/com/trova/backend/controller/AdminDashboardController.java src/test/java/com/trova/backend/controller/AdminDashboardControllerTest.java
git commit -m "feat: 관리자 대시보드 요약 카드"
```

---

### Task 6: `/api/admin/**` 전수 권한 테스트

**Files:**
- Test: `src/test/java/com/trova/backend/controller/AdminEndpointsSecurityTest.java`

**Interfaces:**
- Consumes: 스프링이 등록한 모든 핸들러 매핑(`RequestMappingHandlerMapping`)

- [ ] **Step 1: 테스트 작성**

```java
package com.trova.backend.controller;

import com.trova.backend.entity.User;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * 새 관리자 API가 권한 확인을 빠뜨리지 않게, 등록된 모든 /api/admin/** 매핑을 훑어 비로그인 401·관리자 아님 403인지 본다(#159·#161).
 * 경로 변수는 1로 채운다. 관리자 웹 토큰(aud=admin-web)이 생기면(PR②) "앱 토큰 403"도 여기에 더한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.admin.users=google:sec-admin")
class AdminEndpointsSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    // 임베딩 백필(POST /api/admin/backfill-embeddings)은 외부 API를 부르므로 목 처리 — 권한 단계에서 막혀야 한다.
    @MockitoBean
    private PlaceEmbeddingService placeEmbeddingService;

    private record Endpoint(HttpMethod method, String path) {
    }

    private List<Endpoint> adminEndpoints() {
        List<Endpoint> endpoints = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/admin")) {
                    continue;
                }
                String path = pattern.replaceAll("\\{[^}]+}", "1");
                if (methods.isEmpty()) {
                    endpoints.add(new Endpoint(HttpMethod.GET, path));
                }
                for (RequestMethod m : methods) {
                    endpoints.add(new Endpoint(HttpMethod.valueOf(m.name()), path));
                }
            }
        }
        return endpoints;
    }

    private RequestPostProcessor loginAs(String sub) {
        ClientRegistration google = ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .build();
        return oauth2Login().clientRegistration(google).attributes(attrs -> {
            attrs.put("sub", sub);
            attrs.put("name", sub);
            attrs.put("picture", "https://example.com/p.jpg");
        });
    }

    @Test
    void 모든_관리자_API는_비로그인이면_401_관리자가_아니면_403이다() throws Exception {
        userRepository.save(new User("google", "sec-normal", "일반", null));
        List<Endpoint> endpoints = adminEndpoints();
        // 지금 있는 것: ai-metrics, backfill-embeddings, dashboard 5개 — 매핑을 하나도 못 찾는 실수를 막는다.
        assertThat(endpoints).hasSizeGreaterThanOrEqualTo(7);

        for (Endpoint e : endpoints) {
            int anonymous = mockMvc.perform(request(e.method(), e.path())).andReturn().getResponse().getStatus();
            int normal = mockMvc.perform(request(e.method(), e.path()).with(loginAs("sec-normal")))
                    .andReturn().getResponse().getStatus();
            assertThat(anonymous).as("비로그인 %s %s", e.method(), e.path()).isEqualTo(401);
            assertThat(normal).as("관리자 아님 %s %s", e.method(), e.path()).isEqualTo(403);
        }
    }
}
```

POST에 CSRF가 켜져 있으면 403이 CSRF 때문일 수 있다 — `SecurityConfig`에서 `csrf` 설정을 먼저 확인(`grep -n csrf src/main/java/com/trova/backend/config/SecurityConfig.java`). JWT·무상태라 꺼져 있으면 그대로, 켜져 있으면 요청에 `.with(csrf())`를 붙인다.

- [ ] **Step 2: 실행**

Run: `./gradlew test --tests '*AdminEndpointsSecurityTest' -q`
Expected: PASS(Task 1~5가 모두 `adminGuard.requireAdmin`을 부르므로). 실패하면 실패 메시지의 경로가 권한 확인을 빠뜨린 것 — 그 메서드에 `adminGuard.requireAdmin(authentication)`을 넣는다.

- [ ] **Step 3: 커밋**

```bash
git add src/test/java/com/trova/backend/controller/AdminEndpointsSecurityTest.java
git commit -m "test: 모든 관리자 API의 권한 확인을 자동으로 검사"
```

---

### Task 7: 전체 빌드·로컬 확인·기록·PR

**Files:**
- Modify: `docs/experience-notes.md`

- [ ] **Step 1: 전체 빌드**

Run: `./gradlew build -q`
Expected: BUILD SUCCESSFUL, 실패 0(테스트 수를 기록에 적는다: `grep -ho '<testcase ' build/test-results/test/*.xml | wc -l`).

- [ ] **Step 2: 로컬 서버(개발 DB)에서 확인**

로컬 서버를 이 브랜치로 띄우고(`ADMIN_USERS=google:dev-qa-1` 등 개발 테스트 계정, `.env`는 local-server 작업 폴더에서 복사·커밋 금지), qaflow 테스트 토큰으로:
```bash
T=$(cat ~/.config/qaflow/trova-token)
for p in summary "usage?days=7" "pipeline?days=7" "ai?days=7" "server?days=7"; do
  curl -s -o /dev/null -w "$p %{http_code} %{time_total}s\n" -H "Authorization: Bearer $T" "http://localhost:8080/api/admin/dashboard/$p"
done
```
Expected: 관리자 계정이면 5개 모두 200. 5분 뒤 `server?days=7`의 `snapshots`가 1개 이상. 응답 시간도 기록(로컬·개발 DB라고 표시).

- [ ] **Step 3: 작업 기록**

`docs/experience-notes.md` 끝에 "## 2026-10-11 관리자 대시보드 집계 API·서버 상태 기록 (#161)" 절 — 문제와 조건 / 선택한 방법과 이유(일별 건수는 DB, 백분위·실패 이유는 Java인 이유, 필터로 직접 모은 이유) / 확인한 것(테스트 수, 로컬 응답 코드·시간) / 확인하지 않은 것(운영 배포 전, 웹 화면은 PR③).

- [ ] **Step 4: 커밋·PR**

```bash
git add docs/experience-notes.md
git commit -m "docs: 관리자 대시보드 집계 API 작업 기록"
bash ~/.claude/plugins/cache/taeng-marketplace/devflow/0.5.3/scripts/ship.sh pr "관리자 대시보드 1단계 PR①: 집계 API 5개, 서버 상태 5분 스냅샷, /api/admin/** 전수 권한 테스트."
```
그다음 reviewflow로 PR을 리뷰한다(`/reviewflow:review <PR번호>`, 댓글은 확인 후).
