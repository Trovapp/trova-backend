# 공유 후 일정 초안 자동 생성 (1단계, #136) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 영상 분석이 끝나면 그 영상으로 일정 초안을 자동으로 만들고, 앱 홈에서 "일정이 준비됐어요"로 보여 준다.

**Architecture:** `PlaceExtractionService`가 작업을 DONE으로 바꾼 직후 새 `AutoDraftService.startFor(jobId)`를 부른다.
이 서비스는 건너뛰기 조건을 확인하고 영상 기준 기간 메시지로 `TripDraft(autoCreated=true)`를 저장한 뒤 기존
`TripPlannerService.process`(비동기)를 시작한다. 새 API 두 개(목록·닫기)를 앱 홈 카드와 영상 결과 화면이 쓴다.

**Tech Stack:** Spring Boot 3 / JPA(ddl-auto update) / JUnit5·Mockito·MockMvc(oauth2Login) · Expo RN(react-query)

**Spec:** `docs/superpowers/specs/2026-10-07-auto-draft-and-push-design.md` (1단계 부분)

## Global Constraints
- Gemini 추가 호출 0으로 기간을 정한다: 메시지는 `"N-1박 N일"` 또는 `"당일치기"` (PlanRequestParser가 CODE로 읽음).
- 설정 키 `app.auto-draft.enabled` (기본 true).
- 자동 초안 실패가 분석 작업(ProcessingJob) 상태를 바꾸면 안 된다.
- 커밋 메시지 `타입: 내용`만, AI 서명·트레일러 금지. 커밋 전 `./gradlew build`.
- 사용자에게 보이는 문구는 한국어, 앱 디자인 토큰(`colors`, `space`, `fontSize`) 사용.

## Review Focus
- 같은 영상을 주소 모양만 바꿔 다시 공유(쇼츠 ↔ watch, `?si=`) → 자동 초안이 또 생기면 안 된다 (Task 2 테스트).
- 장소가 0곳인 분석 결과 → 초안을 만들지 않는다 (Task 2 테스트).
- 일차 구분 없는 영상 + 제목에 "1박 2일" → 2일 초안 (Task 2 `autoMessage` 테스트).
- 초안 생성 중 예외(실행기 거절 포함) → 분석 작업은 DONE 그대로 (Task 3 테스트).
- 남의 초안 닫기 → 404, 승인된 초안 닫기 → 409 (Task 4 테스트).

---

### Task 1: 초안에 자동 생성·닫음 표시

**Files:**
- Modify: `src/main/java/com/trova/backend/entity/TripDraft.java`
- Modify: `src/main/java/com/trova/backend/repository/TripDraftRepository.java`

**Interfaces:**
- Produces: `TripDraft.auto(User, List<Long>, String)` 정적 생성자, `isAutoCreated()`, `getDismissedAt()`, `dismiss()`;
  `TripDraftRepository.findByUserAndAutoCreatedTrue(User)`,
  `findByUserAndAutoCreatedTrueAndDismissedAtIsNullAndStatusInOrderByCreatedAtDesc(User, List<TripDraftStatus>)`

- [ ] **Step 1:** `TripDraft`에 칸 추가

```java
    // 공유한 영상 분석이 끝나 자동으로 만든 초안(#136). 닫은 초안은 행을 남겨 같은 영상의 중복 생성을 막는다.
    @Column(name = "auto_created", nullable = false)
    private boolean autoCreated = false;

    @Column(name = "dismissed_at")
    private LocalDateTime dismissedAt;

    public static TripDraft auto(User user, List<Long> jobIds, String message) {
        TripDraft draft = new TripDraft(user, jobIds, message);
        draft.autoCreated = true;
        return draft;
    }

    public void dismiss() {
        this.dismissedAt = LocalDateTime.now();
    }

    public boolean isAutoCreated() { return autoCreated; }
    public LocalDateTime getDismissedAt() { return dismissedAt; }
```
  기존 행이 있는 DB에서 `nullable=false` 칸이 추가되도록 `columnDefinition = "boolean default false"`를 붙인다.

- [ ] **Step 2:** 저장소 메서드 2개 추가(위 Produces). 컴파일 확인: `./gradlew compileJava`
- [ ] **Step 3:** 커밋은 Task 2와 함께(이 단계만으로는 시험할 동작이 없음).

### Task 2: AutoDraftService

**Files:**
- Create: `src/main/java/com/trova/backend/service/AutoDraftService.java`
- Create: `src/test/java/com/trova/backend/service/AutoDraftServiceTest.java` (autoMessage 단위)
- Create: `src/test/java/com/trova/backend/service/AutoDraftServiceIntegrationTest.java`
- Modify: `src/main/resources/application.yml` (`app.auto-draft.enabled: ${AUTO_DRAFT_ENABLED:true}`)

**Interfaces:**
- Consumes: Task 1, `TripService.findExistingTripForVideo(User, ProcessingJob)`, `VideoKey.of(String)`,
  `PlanRequestParser.parseDays(String)`(같은 패키지가 아니므로 `public static`으로 바꾼다), `TripPlannerService.process(Long)`
- Produces: `void startFor(Long jobId)`, `static String autoMessage(List<SavedPlace> places, String title)`

- [ ] **Step 1: 실패하는 단위 테스트**

```java
class AutoDraftServiceTest {
    private static SavedPlace place(Integer day) {
        return new SavedPlace(null, null, "곳", "제주", "attraction", 33.0, 126.0, day, 1);
    }
    @Test void 영상에_일차_구분이_있으면_그_일수() {
        assertThat(AutoDraftService.autoMessage(List.of(place(1), place(3), place(2)), "제주 여행")).isEqualTo("2박 3일");
    }
    @Test void 일차가_없으면_제목의_박일() {
        assertThat(AutoDraftService.autoMessage(List.of(place(null)), "부산 1박 2일 코스")).isEqualTo("1박 2일");
    }
    @Test void 둘_다_없으면_당일치기() {
        assertThat(AutoDraftService.autoMessage(List.of(place(1)), null)).isEqualTo("당일치기");
    }
}
```
  Run: `./gradlew test --tests '*AutoDraftServiceTest'` → 컴파일 실패(클래스 없음)

- [ ] **Step 2: 구현**

```java
@Service
public class AutoDraftService {
    private static final Logger log = LoggerFactory.getLogger(AutoDraftService.class);
    private final boolean enabled;
    private final ProcessingJobRepository processingJobRepository;
    private final SavedPlaceRepository savedPlaceRepository;
    private final TripDraftRepository tripDraftRepository;
    private final TripService tripService;
    private final TripPlannerService tripPlannerService;

    // 생성자: @Value("${app.auto-draft.enabled:true}") boolean enabled + 위 5개

    /** 분석이 끝난 직후 부른다. 어떤 예외도 밖으로 던지지 않는다 — 분석 결과는 초안과 상관없이 완료다. */
    public void startFor(Long jobId) {
        try {
            Long draftId = create(jobId);
            if (draftId == null) return;
            try {
                tripPlannerService.process(draftId);
            } catch (TaskRejectedException e) {
                tripDraftRepository.deleteById(draftId);
                log.warn("ProcessingJob {} 자동 초안 대기열이 가득 차 건너뜀", jobId);
            }
        } catch (Exception e) {
            log.error("ProcessingJob {} 자동 초안 생성 실패", jobId, e);
        }
    }

    Long create(Long jobId) {
        if (!enabled) return null;
        ProcessingJob job = processingJobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != JobStatus.DONE) return null;
        List<SavedPlace> places = savedPlaceRepository.findByProcessingJob(job);
        if (places.isEmpty()) return null;
        User user = job.getUser();
        if (tripService.findExistingTripForVideo(user, job).isPresent()) return null;
        String key = VideoKey.of(job.getSourceUrl());
        boolean exists = tripDraftRepository.findByUserAndAutoCreatedTrue(user).stream()
                .flatMap(d -> d.getJobIds().stream())
                .map(processingJobRepository::findById).flatMap(Optional::stream)
                .anyMatch(j -> VideoKey.of(j.getSourceUrl()).equals(key));
        if (exists) return null;
        return tripDraftRepository.save(TripDraft.auto(user, List.of(jobId), autoMessage(places, job.getTitle()))).getId();
    }

    static String autoMessage(List<SavedPlace> places, String title) {
        int maxDay = places.stream().map(SavedPlace::getDayNumber).filter(Objects::nonNull).max(Integer::compare).orElse(0);
        int days = maxDay >= 2 ? maxDay : (title == null ? 1 : PlanRequestParser.parseDays(title).orElse(1));
        return days <= 1 ? "당일치기" : (days - 1) + "박 " + days + "일";
    }
}
```
  `create`는 같은 빈 안에서 불리므로 `@Transactional`이 걸리지 않는다 — 트랜잭션 없이 각 저장소 호출이 따로 커밋되는 것으로 충분(읽기 후 저장 1회).
  지연 로딩이 필요한 `job.getUser()`는 `user.getId()`만 쓰므로 문제없다(프록시 id).

- [ ] **Step 3:** 단위 테스트 통과 확인.
- [ ] **Step 4: 통합 테스트**(`@SpringBootTest`, `@MockitoBean TripPlannerService`로 process 호출만 확인, 정리는 TripDraftApprovalServiceIntegrationTest와 같은 방식)
  - `분석이_끝나면_자동_초안을_만든다`: DONE 작업 + 장소 2곳(day 1·2) → 초안 1개, autoCreated, message "1박 2일", process(draftId) 호출
  - `주소_모양이_달라도_같은_영상이면_다시_만들지_않는다`: `https://youtube.com/shorts/abc` 작업으로 생성 후 `https://youtu.be/abc?si=x` 작업 → 초안 1개
  - `장소가_없으면_만들지_않는다`, `이미_만든_여행이_있으면_만들지_않는다`(trip_place가 그 영상 장소를 가리킴)
  - `설정이_꺼져_있으면_만들지_않는다`: `ReflectionTestUtils.setField(service, "enabled", false)` 후 원복
  Run: `./gradlew test --tests '*AutoDraftService*'` → PASS
- [ ] **Step 5:** 커밋 `feat: 분석이 끝나면 일정 초안을 자동으로 만들기`

### Task 3: 분석 완료에 연결

**Files:**
- Modify: `src/main/java/com/trova/backend/service/PlaceExtractionService.java` (생성자 + `markDone` 뒤)
- Modify: `src/test/java/com/trova/backend/service/PlaceExtractionServiceTest.java`

- [ ] **Step 1: 실패하는 테스트** — `@Mock AutoDraftService autoDraftService`를 추가하고 `serviceWith`에 넘긴다.
  기존 성공 테스트 하나에 `verify(autoDraftService).startFor(jobId)`를 추가하고, 새 테스트
  `자동_초안이_실패해도_분석은_완료로_남는다`: `doThrow(new RuntimeException("x")).when(autoDraftService).startFor(any())` →
  `verify(lifecycleService).markDone(jobId)`, `verify(lifecycleService, never()).markFailed(any(), any())`.
- [ ] **Step 2:** 구현 — `lifecycleService.markDone(jobId);` 다음 줄에

```java
            // 공유만 해 두면 일정까지 짜 두게(#136). 실패해도 분석 결과는 이미 완료다.
            try {
                autoDraftService.startFor(jobId);
            } catch (RuntimeException e) {
                log.warn("ProcessingJob {} 자동 초안 시작 실패", jobId, e);
            }
```
- [ ] **Step 3:** `./gradlew test --tests '*PlaceExtractionServiceTest'` PASS → 커밋 `feat: 영상 분석이 끝나면 자동 초안 시작`

### Task 4: 목록·닫기 API

**Files:**
- Modify: `src/main/java/com/trova/backend/controller/TripDraftController.java`
- Create: `src/test/java/com/trova/backend/controller/TripDraftAutoControllerTest.java` (`@SpringBootTest @AutoConfigureMockMvc @Transactional`, `loginAs`는 TripControllerTest와 같은 oauth2Login)

**Interfaces:**
- Produces: `GET /api/trip-drafts/auto` → `[{draftId, status, jobId, videoTitle, days, createdAt}]`;
  `POST /api/trip-drafts/{id}/dismiss` → 204 / 404(없음·남의 것) / 409(APPROVED)

- [ ] **Step 1: 실패하는 테스트**
  - 목록: 내 자동 초안 READY 1개, PROCESSING 1개, 닫은 것 1개, 수동 초안 1개, 남의 자동 초안 1개 → 2개만, 최신순
  - 닫기: 내 것 204 후 목록에서 빠짐 / 남의 것 404 / APPROVED 409
- [ ] **Step 2: 구현**

```java
    public record AutoDraftResponse(Long draftId, String status, Long jobId, String videoTitle, Integer days, String createdAt) {
    }

    private static final List<TripDraftStatus> AUTO_VISIBLE =
            List.of(TripDraftStatus.PENDING, TripDraftStatus.PROCESSING, TripDraftStatus.READY);

    @GetMapping("/api/trip-drafts/auto")
    public List<AutoDraftResponse> autoDrafts(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        return tripDraftRepository
                .findByUserAndAutoCreatedTrueAndDismissedAtIsNullAndStatusInOrderByCreatedAtDesc(user, AUTO_VISIBLE)
                .stream().limit(10)
                .map(d -> {
                    Long jobId = d.getJobIds().get(0);
                    String title = processingJobRepository.findById(jobId).map(ProcessingJob::getTitle).orElse(null);
                    return new AutoDraftResponse(d.getId(), d.getStatus().name(), jobId, title, d.getDays(), d.getCreatedAt().toString());
                }).toList();
    }

    @PostMapping("/api/trip-drafts/{id}/dismiss")
    @Transactional
    public ResponseEntity<Void> dismiss(Authentication authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        TripDraft draft = tripDraftRepository.findById(id).filter(d -> d.getUser().getId().equals(user.getId())).orElse(null);
        if (draft == null) return ResponseEntity.notFound().build();
        if (draft.getStatus() == TripDraftStatus.APPROVED) return ResponseEntity.status(HttpStatus.CONFLICT).build();
        draft.dismiss();
        tripDraftRepository.save(draft);
        return ResponseEntity.noContent().build();
    }
```
  컨트롤러 생성자에 `ProcessingJobRepository` 추가.
- [ ] **Step 3:** PASS → `./gradlew build` 전체 통과 → 커밋 `feat: 자동 일정 초안 목록·닫기 API 추가`

### Task 5: 앱 (trova-app, 브랜치 `feat/auto-draft`)

**Files:**
- Modify: `src/lib/api/tripDrafts.ts` — `listAutoDrafts()`, `dismissTripDraft(id)`, 타입 `AutoDraft`
- Modify: `src/navigation/types.ts` — `PlanTrip: { draftId?: number } | undefined`
- Modify: `src/screens/PlanTripScreen.tsx` — `route.params?.draftId`가 있으면 `draftId` 초기값으로(바로 초안 화면)
- Create: `src/components/ReadyDraftCard.tsx` — 홈 카드(READY: "일정이 준비됐어요 · {제목} {N일}", 진행 중: "일정을 짜고 있어요", 닫기 버튼 → dismiss 후 목록 다시 불러오기)
- Modify: `src/screens/HomeScreen.tsx` — 입력 히어로 아래에 카드, `queryKey: ["autoDrafts"]`, 진행 중이 있으면 5초마다 다시 조회, 당겨서 새로고침에 포함
- Modify: `src/screens/VideoGroupScreen.tsx` — 만든 여행이 없고 그 jobId의 자동 초안이 READY면 아래 버튼 "준비된 일정 보기" → `PlanTrip({draftId})`
- Modify: `src/screens/ProcessingScreen.tsx` — 안내 문구에 "끝나면 일정도 짜 둘게요" 추가
- 승인 성공 시 `["autoDrafts"]`도 무효화.

- [ ] `npx tsc --noEmit` 통과, 디자인 토큰 검사(기존 스크립트) 통과
- [ ] 커밋 `feat: 자동으로 짠 일정 초안을 홈과 영상 결과에서 보여주기`

### Task 6: 확인·기록·PR

- [ ] 로컬 서버(개발 DB)를 이 브랜치 JAR로 띄우고 QA 시뮬레이터에서: 링크 공유(붙여넣기) → 분석 → 홈 카드 "일정을 짜고 있어요" → "준비됐어요" → 초안 → 여행 만들기 → 장소 메모. 닫기도 확인.
- [ ] 같은 영상 재분석 시 카드가 새로 생기지 않는지 확인. Gemini 사용량 전후 기록.
- [ ] 테스트 데이터 정리, 백엔드·앱 `docs/experience-notes.md` 기록(범위·방법 비교·확인 결과, 로컬/운영 구분).
- [ ] 백엔드 PR(`Closes #136`), 앱 브랜치 푸시. 머지·배포는 사용자 확인 후.
