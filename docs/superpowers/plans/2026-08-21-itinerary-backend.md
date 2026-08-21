# 일정형 영상 자동 일정 뷰 — 백엔드/파이프라인 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gemini가 영상 내용에서 일자별(1일차/2일차...) 구조를 추론해서 각
장소에 dayNumber/orderInDay를 부여하고, 이 값이 파이프라인 → 백엔드 →
`GET /api/places` 응답까지 그대로 전달되게 만든다.

**Architecture:** 새 Gemini 호출/새 엔티티 없이, 기존 2단계 필터링
프롬프트(`FILTER_PROMPT`)를 확장하고 `SavedPlace`에 nullable 컬럼 2개만
추가한다. 이 계획 하나만으로 `GET /api/places` 응답에서 일정형 장소가
dayNumber/orderInDay와 함께 나오는 것까지 완성/검증한다(프론트엔드는
별도 계획).

**Tech Stack:** Spring Boot(Java 21) + JPA/Hibernate(`ddl-auto: update`),
Python(stdlib) + Gemini REST API. 작업은 `trova-backend` 레포의
`.worktrees/feat-saved-places-pipeline` 워크트리, `feat/saved-places-pipeline`
브랜치(이미 열려있는 PR #2)에서 진행한다.

**Spec:** `docs/superpowers/specs/2026-08-21-itinerary-view-design.md`

## Global Constraints

- 새 Gemini API 호출을 추가하지 않는다 — 기존 `FILTER_PROMPT` 호출 안에서
  일정 판단까지 같이 한다 (무료 티어 RPM 한도 때문).
- 새 JPA 엔티티를 만들지 않는다 — `SavedPlace`에 `dayNumber`/`orderInDay`
  (둘 다 nullable Integer)만 추가한다.
- 일정형 판단은 `ProcessingJob`(영상) 단위 — 한 영상에서 나온 장소는
  전부 일정형이거나 전부 아니거나 둘 중 하나로 간주한다.
- `ddl-auto: update`이므로 수동 마이그레이션 스크립트를 작성하지 않는다.
- 커밋 전 `./gradlew build` 통과 확인(CLAUDE.md 규칙).
- 이 레포에는 `pipeline-test/*.py`용 자동화 테스트 프레임워크가 없다
  (기존 관례 — `PROGRESS.md` 참고). Task 1의 검증은 실제 Gemini 호출로
  하는 수동 확인이며, 이는 placeholder가 아니라 이 레포의 기존 검증
  방식을 그대로 따르는 것이다.

---

### Task 1: 파이프라인 — 일정형 판단 및 dayNumber/orderInDay 추출

**Files:**
- Modify: `pipeline-test/extract_places.py`

**Interfaces:**
- Produces: `extract_places()`가 반환하는 각 dict가 이제
  `dayNumber`(int|null), `orderInDay`(int|null) 키를 가질 수 있음
  (Task 2에서 Java `ExtractedPlace`가 이 JSON 키 이름을 그대로 매핑)

- [ ] **Step 1: `FILTER_PROMPT`에 일정 판단 지시와 필드 설명 추가**

`pipeline-test/extract_places.py`의 `FILTER_PROMPT` 상수를 아래로
교체한다(기존 "포함 기준"/"제외 기준" 섹션은 그대로 두고, "각 항목은
다음 필드를 가집니다" 섹션과 마지막 문장 사이에 일정 판단 문단을,
필드 목록에 두 줄을 추가):

```python
FILTER_PROMPT = """당신은 여행 영상 자막/음성/화면 텍스트에서 지도에 저장할 만한 구체적인 장소를 추출하는 도구입니다.

포함 기준:
- 식당, 카페, 관광지, 숙소, 상점 등 지도에서 검색해 갈 수 있는 구체적 지점(동네, 랜드마크, 상호명 포함)
- 오디오에서 스쳐 지나가듯 짧게 언급된 곳도 놓치지 말고 전부 포함하세요
- 화면에 찍힌 위치 태그/캡션도 반드시 확인해서 포함하세요
- 아래 "1차 후보 목록"에 있는 이름은 누락 없이 전부 검토하세요 — 포함/제외 기준에
  따라 최종적으로 빠지는 건 괜찮지만, 검토 자체를 건너뛰지 마세요

제외 기준:
- 시/도/광역시/특별시 등 행정구역 단위의 넓은 지역명 자체(예: "서울", "부산", "제주도")는 name으로 쓰지 마세요.
  다른 지역과 비교하거나 배경 설명으로만 언급된 경우가 많고, 지도에 찍을 지점이 아닙니다.
  단, "해운대"처럼 그 안의 동네/랜드마크가 함께 언급됐다면 그 동네/랜드마크만 name으로 쓰고,
  상위 지역명은 region 필드에 넣으세요.
- 일반명사만 있고 고유명사가 없는 경우 (예: "카페", "시장" 단독)는 제외하세요.

일정 구조 판단:
이 영상이 여행 일정을 일자별(1일차, 2일차...)로 소개하는 구조인지 판단하세요.
자막/오디오/화면 텍스트에 "1일차", "Day 1", "첫째 날" 같은 명시적 표현이 있거나,
장소들이 명확하게 날짜 단위로 묶여 순서대로 소개되면 일정형입니다. 단순히 여러
장소를 나열만 하고 날짜 구분이 없으면 일정형이 아닙니다.

일정형이면 각 장소에 dayNumber(몇 일차인지)와 orderInDay(그 날 안에서 몇 번째로
소개됐는지)를 채우세요. 일정형이 아니면 두 필드 모두 null로 두세요.

각 항목은 다음 필드를 가집니다:
- name: 장소의 고유 이름
- region: 알 수 있는 상위 지역/도시명 (모르면 null)
- category: "restaurant" | "cafe" | "attraction" | "lodging" | "shopping" | "other" 중 하나
- confidence: 0~1 사이 숫자 (얼마나 확실한 장소명인지)
- dayNumber: 몇 일차인지 (1부터 시작하는 정수, 일정형이 아니면 null)
- orderInDay: 그 날 안에서의 순서 (1부터 시작하는 정수, 일정형이 아니면 null)

장소가 전혀 없으면 빈 배열 []을 반환하세요. JSON 배열 외의 다른 텍스트는 출력하지 마세요.
"""
```

- [ ] **Step 2: 일정형 샘플로 수동 실행해서 확인**

`pipeline-test/.env`에 실제 `GEMINI_API_KEY`가 있는지 먼저 확인한다
(`cat pipeline-test/.env`). 아래처럼 일자별 구조를 명시한 가짜 자막
파일을 만들어서 실제로 돌려본다:

```bash
cd pipeline-test
cat > /tmp/itinerary_sample.txt <<'EOF'
1일차. 부산 여행 시작! 첫 번째로 해운대해수욕장에 도착했어요. 바다가 정말 예뻐요.
점심은 근처 삼진어묵에서 먹었습니다. 어묵이 정말 맛있어요.
저녁에는 광안리해수욕장으로 이동해서 야경을 봤어요.

2일차. 둘째 날은 감천문화마을부터 시작합니다. 알록달록한 집들이 인상적이에요.
그다음 국제시장에 들러서 구경했고, 마지막으로 자갈치시장에서 회를 먹었습니다.
EOF
python3 extract_places.py /tmp/itinerary_sample.txt
```

기대 결과: JSON 배열에 총 6개 장소(해운대해수욕장, 삼진어묵, 광안리해수욕장,
감천문화마을, 국제시장, 자갈치시장)가 나오고, 앞 3개는 `dayNumber: 1`
(orderInDay 1,2,3), 뒤 3개는 `dayNumber: 2`(orderInDay 1,2,3)로 채워져
있어야 한다. Gemini 응답이라 정확한 표현은 다를 수 있지만, dayNumber가
1과 2로 나뉘고 각 day 안에서 orderInDay가 1부터 순서대로 매겨지는지가
핵심이다. 기대와 다르면 프롬프트 문구를 조정하고 다시 실행한다.

- [ ] **Step 3: 비일정형 샘플로도 회귀 확인 (기존 동작 안 깨졌는지)**

```bash
cat > /tmp/normal_sample.txt <<'EOF'
부산 여행 가서 해운대해수욕장이랑 광안리해수욕장 둘 다 가봤어요.
맛있었던 삼진어묵도 추천합니다.
EOF
python3 extract_places.py /tmp/normal_sample.txt
```

기대 결과: 장소는 그대로 추출되지만 `dayNumber`/`orderInDay`가 전부
`null`이어야 한다(날짜 구분 표현이 없으므로).

- [ ] **Step 4: 커밋**

```bash
git add pipeline-test/extract_places.py
git commit -m "feat: 일정형 영상 감지 및 dayNumber/orderInDay 추출 추가"
```

---

### Task 2: `ExtractedPlace`에 dayNumber/orderInDay 필드 추가

**Files:**
- Modify: `src/main/java/com/trova/backend/pipeline/ExtractedPlace.java`
- Modify: `src/test/java/com/trova/backend/pipeline/PipelineOutputParserTest.java`

**Interfaces:**
- Consumes: Task 1에서 파이프라인이 반환하는 JSON의 `dayNumber`/`orderInDay` 키
- Produces: `ExtractedPlace.dayNumber()`, `ExtractedPlace.orderInDay()` —
  Task 3(`ProcessingJobLifecycleService.savePlace`)에서 사용

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/trova/backend/pipeline/PipelineOutputParserTest.java`에
아래 두 테스트를 추가한다(기존 4개 테스트는 그대로 둠):

```java
    @Test
    void dayNumber와_orderInDay가_있으면_함께_파싱된다() {
        String stdout = """
                [
                  {"name": "해운대", "region": "부산", "category": "attraction", "confidence": 0.95, "dayNumber": 1, "orderInDay": 1},
                  {"name": "서면", "region": "부산", "category": "shopping", "confidence": 0.9, "dayNumber": 2, "orderInDay": 1}
                ]
                """;

        List<ExtractedPlace> places = PipelineOutputParser.parse(stdout);

        assertThat(places.get(0).dayNumber()).isEqualTo(1);
        assertThat(places.get(0).orderInDay()).isEqualTo(1);
        assertThat(places.get(1).dayNumber()).isEqualTo(2);
    }

    @Test
    void dayNumber와_orderInDay가_없으면_null로_파싱된다() {
        String stdout = """
                [
                  {"name": "해운대", "region": "부산", "category": "attraction", "confidence": 0.95}
                ]
                """;

        List<ExtractedPlace> places = PipelineOutputParser.parse(stdout);

        assertThat(places.get(0).dayNumber()).isNull();
        assertThat(places.get(0).orderInDay()).isNull();
    }
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.pipeline.PipelineOutputParserTest"`
Expected: 컴파일 에러 — `ExtractedPlace`에 `dayNumber()`/`orderInDay()`
메서드가 없음 (레코드에 필드가 아직 없으므로)

- [ ] **Step 3: `ExtractedPlace`에 필드 추가**

`src/main/java/com/trova/backend/pipeline/ExtractedPlace.java` 전체를
아래로 교체:

```java
package com.trova.backend.pipeline;

public record ExtractedPlace(
        String name, String region, String category, Double confidence,
        Integer dayNumber, Integer orderInDay
) {
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.pipeline.PipelineOutputParserTest"`
Expected: PASS (6개 테스트 전부)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/trova/backend/pipeline/ExtractedPlace.java \
        src/test/java/com/trova/backend/pipeline/PipelineOutputParserTest.java
git commit -m "feat: ExtractedPlace에 dayNumber/orderInDay 필드 추가"
```

---

### Task 3: `SavedPlace` 엔티티에 dayNumber/orderInDay 컬럼 추가

**Files:**
- Modify: `src/main/java/com/trova/backend/entity/SavedPlace.java`
- Modify: `src/test/java/com/trova/backend/repository/SavedPlaceRepositoryTest.java`

**Interfaces:**
- Consumes: 없음 (엔티티 자체 변경)
- Produces: `SavedPlace`에 새 8-인자 생성자
  `SavedPlace(ProcessingJob, User, String placeName, String region, String category, Double latitude, Double longitude, Integer dayNumber, Integer orderInDay)`
  및 `getDayNumber()`/`getOrderInDay()`. **기존 7-인자 생성자는 그대로
  유지**(내부적으로 새 생성자에 위임, dayNumber/orderInDay는 null) —
  기존 테스트 파일들(`PlacesControllerTest`,
  `ProcessingJobLifecycleServiceIntegrationTest`, `UserAccountServiceTest`,
  `UsersControllerTest`)이 이미 7-인자 생성자를 쓰고 있으므로 시그니처를
  깨지 않기 위함. Task 4에서 `ProcessingJobLifecycleService.savePlace()`가
  새 8-인자 생성자를 사용하게 됨.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/trova/backend/repository/SavedPlaceRepositoryTest.java`에
아래 두 테스트를 추가:

```java
    @Test
    void dayNumber와_orderInDay가_저장되고_조회된다() {
        User user = userRepository.save(new User("google", "6", "일정유저", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/itinerary", SourcePlatform.YOUTUBE));
        SavedPlace place = savedPlaceRepository.save(
                new SavedPlace(job, user, "해운대", "부산", "attraction", 35.16, 129.16, 1, 1));

        SavedPlace reloaded = savedPlaceRepository.findById(place.getId()).orElseThrow();

        assertThat(reloaded.getDayNumber()).isEqualTo(1);
        assertThat(reloaded.getOrderInDay()).isEqualTo(1);
    }

    @Test
    void 일정형이_아니면_dayNumber와_orderInDay가_null이다() {
        User user = userRepository.save(new User("google", "7", "일반유저", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/normal", SourcePlatform.YOUTUBE));
        SavedPlace place = savedPlaceRepository.save(
                new SavedPlace(job, user, "장소", null, "cafe", null, null));

        SavedPlace reloaded = savedPlaceRepository.findById(place.getId()).orElseThrow();

        assertThat(reloaded.getDayNumber()).isNull();
        assertThat(reloaded.getOrderInDay()).isNull();
    }
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.repository.SavedPlaceRepositoryTest"`
Expected: 컴파일 에러 — 8-인자 `SavedPlace` 생성자가 없음

- [ ] **Step 3: `SavedPlace`에 필드/생성자/getter 추가**

`src/main/java/com/trova/backend/entity/SavedPlace.java` 전체를 아래로
교체:

```java
package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "saved_places")
public class SavedPlace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "processing_job_id", nullable = false)
    private ProcessingJob processingJob;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "place_name", nullable = false)
    private String placeName;

    private String region;

    private String category;

    private Double latitude;

    private Double longitude;

    @Column(name = "source_url", nullable = false)
    private String sourceUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_platform", nullable = false)
    private SourcePlatform sourcePlatform;

    @Column(name = "day_number")
    private Integer dayNumber;

    @Column(name = "order_in_day")
    private Integer orderInDay;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected SavedPlace() {
    }

    public SavedPlace(ProcessingJob processingJob, User user, String placeName, String region,
                       String category, Double latitude, Double longitude) {
        this(processingJob, user, placeName, region, category, latitude, longitude, null, null);
    }

    public SavedPlace(ProcessingJob processingJob, User user, String placeName, String region,
                       String category, Double latitude, Double longitude,
                       Integer dayNumber, Integer orderInDay) {
        this.processingJob = processingJob;
        this.user = user;
        this.placeName = placeName;
        this.region = region;
        this.category = category;
        this.latitude = latitude;
        this.longitude = longitude;
        this.sourceUrl = processingJob.getSourceUrl();
        this.sourcePlatform = processingJob.getSourcePlatform();
        this.dayNumber = dayNumber;
        this.orderInDay = orderInDay;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public ProcessingJob getProcessingJob() { return processingJob; }
    public User getUser() { return user; }
    public String getPlaceName() { return placeName; }
    public String getRegion() { return region; }
    public String getCategory() { return category; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public String getSourceUrl() { return sourceUrl; }
    public SourcePlatform getSourcePlatform() { return sourcePlatform; }
    public Integer getDayNumber() { return dayNumber; }
    public Integer getOrderInDay() { return orderInDay; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.repository.SavedPlaceRepositoryTest"`
Expected: PASS (4개 테스트 전부)

- [ ] **Step 5: 전체 빌드로 다른 테스트 안 깨졌는지 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (7-인자 생성자를 쓰는 기존 테스트들도 그대로 통과)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/trova/backend/entity/SavedPlace.java \
        src/test/java/com/trova/backend/repository/SavedPlaceRepositoryTest.java
git commit -m "feat: SavedPlace에 dayNumber/orderInDay 컬럼 추가"
```

---

### Task 4: `ProcessingJobLifecycleService.savePlace()`가 dayNumber/orderInDay를 저장

**Files:**
- Modify: `src/main/java/com/trova/backend/service/ProcessingJobLifecycleService.java`
- Modify: `src/test/java/com/trova/backend/service/ProcessingJobLifecycleServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `ExtractedPlace.dayNumber()`/`orderInDay()`(Task 2),
  `SavedPlace`의 8-인자 생성자(Task 3)
- Produces: `savePlace()`가 저장하는 `SavedPlace`에 dayNumber/orderInDay가
  채워짐 — Task 5(`PlacesController`)가 이 값을 응답으로 노출

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/trova/backend/service/ProcessingJobLifecycleServiceIntegrationTest.java`
상단 import에 아래 세 줄 추가:

```java
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.geocoding.GeocodingResult;
import com.trova.backend.pipeline.ExtractedPlace;
```

(`SavedPlace`는 이미 다른 곳에서 안 쓰이므로 새로 추가, 나머지 둘도 신규)

파일 맨 아래(마지막 `}` 앞)에 테스트 추가:

```java
    @Test
    void savePlace가_dayNumber와_orderInDay를_함께_저장한다() {
        ProcessingJob job = newJob();
        ExtractedPlace extracted = new ExtractedPlace("해운대", "부산", "attraction", 0.95, 1, 1);
        GeocodingResult geocoded = new GeocodingResult(35.16, 129.16);

        lifecycleService.savePlace(job.getId(), extracted, geocoded);

        SavedPlace saved = savedPlaceRepository.findByUserOrderByCreatedAtDescIdDesc(job.getUser()).get(0);
        assertThat(saved.getDayNumber()).isEqualTo(1);
        assertThat(saved.getOrderInDay()).isEqualTo(1);
    }
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.service.ProcessingJobLifecycleServiceIntegrationTest"`
Expected: FAIL — `saved.getDayNumber()`가 `null`을 반환(아직 `savePlace()`가
전달 안 하므로), assertion 실패

- [ ] **Step 3: `savePlace()`가 새 생성자를 쓰도록 수정**

`src/main/java/com/trova/backend/service/ProcessingJobLifecycleService.java`의
`savePlace` 메서드를 아래로 교체:

```java
    @Transactional
    public void savePlace(Long jobId, ExtractedPlace extracted, GeocodingResult geocoded) {
        ProcessingJob job = getJob(jobId);
        savedPlaceRepository.save(new SavedPlace(
                job,
                job.getUser(),
                extracted.name(),
                extracted.region(),
                extracted.category(),
                geocoded.latitude(),
                geocoded.longitude(),
                extracted.dayNumber(),
                extracted.orderInDay()
        ));
    }
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.service.ProcessingJobLifecycleServiceIntegrationTest"`
Expected: PASS (5개 테스트 전부)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/trova/backend/service/ProcessingJobLifecycleService.java \
        src/test/java/com/trova/backend/service/ProcessingJobLifecycleServiceIntegrationTest.java
git commit -m "feat: savePlace가 dayNumber/orderInDay를 함께 저장하도록 수정"
```

---

### Task 5: `GET /api/places` 응답에 dayNumber/orderInDay 노출

**Files:**
- Modify: `src/main/java/com/trova/backend/controller/PlacesController.java`
- Modify: `src/test/java/com/trova/backend/controller/PlacesControllerTest.java`

**Interfaces:**
- Consumes: `SavedPlace.getDayNumber()`/`getOrderInDay()`(Task 3)
- Produces: `PlaceResponse` JSON에 `dayNumber`/`orderInDay` 필드 — 이
  계획의 최종 산출물(프론트엔드 계획이 이 API 응답을 그대로 소비)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/trova/backend/controller/PlacesControllerTest.java`
파일 맨 아래(마지막 `}` 앞)에 테스트 추가:

```java
    @Test
    void 일정형_장소는_dayNumber와_orderInDay를_반환한다() throws Exception {
        User me = userRepository.save(new User("google", "hhh", "일정유저", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(me, "https://youtu.be/itinerary2", SourcePlatform.YOUTUBE));
        savedPlaceRepository.save(
                new SavedPlace(job, me, "해운대", "부산", "attraction", 35.16, 129.16, 1, 1));

        mockMvc.perform(get("/api/places").with(loginAs("hhh", "일정유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dayNumber").value(1))
                .andExpect(jsonPath("$[0].orderInDay").value(1));
    }

    @Test
    void 일정형이_아닌_장소는_dayNumber가_null이다() throws Exception {
        User me = userRepository.save(new User("google", "iii", "일반유저2", null));
        ProcessingJob job = processingJobRepository.save(
                new ProcessingJob(me, "https://youtu.be/normal2", SourcePlatform.YOUTUBE));
        savedPlaceRepository.save(
                new SavedPlace(job, me, "장소", null, "cafe", null, null));

        mockMvc.perform(get("/api/places").with(loginAs("iii", "일반유저2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dayNumber").value(org.hamcrest.Matchers.nullValue()));
    }
```

(`application.yml`에 Jackson `default-property-inclusion` 설정이 없어
기본값 `ALWAYS`가 적용된다 — null 필드도 `"dayNumber":null`로 응답에
포함되므로 `doesNotExist()`가 아니라 `nullValue()`로 확인한다.)

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.controller.PlacesControllerTest"`
Expected: 두 테스트 다 FAIL — 응답 JSON에 `dayNumber`/`orderInDay` 키
자체가 없어서 `jsonPath("$[0].dayNumber")`가 매칭 안 됨

- [ ] **Step 3: `PlaceResponse`에 필드 추가**

`src/main/java/com/trova/backend/controller/PlacesController.java`의
`PlaceResponse` 레코드를 아래로 교체:

```java
    public record PlaceResponse(
            Long id, String placeName, String region, String category,
            Double latitude, Double longitude, String sourceUrl,
            String sourcePlatform, String createdAt, Integer dayNumber, Integer orderInDay
    ) {
        static PlaceResponse from(SavedPlace place) {
            return new PlaceResponse(
                    place.getId(), place.getPlaceName(), place.getRegion(), place.getCategory(),
                    place.getLatitude(), place.getLongitude(), place.getSourceUrl(),
                    place.getSourcePlatform().name(), place.getCreatedAt().toString(),
                    place.getDayNumber(), place.getOrderInDay()
            );
        }
    }
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.controller.PlacesControllerTest"`
Expected: PASS 전부

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/trova/backend/controller/PlacesController.java \
        src/test/java/com/trova/backend/controller/PlacesControllerTest.java
git commit -m "feat: GET /api/places 응답에 dayNumber/orderInDay 노출"
```

---

### Task 6: 전체 검증 + 문서 갱신

**Files:**
- Modify: `PROGRESS.md`
- Modify: `TODO.md`

**Interfaces:** 없음 (마무리 작업)

- [ ] **Step 1: 전체 빌드 재확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Supabase 스키마에 새 컬럼이 실제로 생겼는지 확인**

애플리케이션을 한 번 기동(`./gradlew bootRun` 또는 이미 떠 있는
프로세스 재시작)한 뒤, `saved_places` 테이블에
`day_number`/`order_in_day` 컬럼이 생겼는지 Supabase에 직접 접속해서
확인(이 세션에서 앞서 썼던 방식 — `.env`의
`SPRING_DATASOURCE_USERNAME`/`PASSWORD`로 `psycopg2` 등을 이용해
`information_schema.columns` 조회, 또는 이미 설치돼 있다면 `psql`).

- [ ] **Step 3: TODO.md/PROGRESS.md 갱신**

`TODO.md`의 "일정 자동 생성"(백로그) 항목을 찾아서, 이 계획으로 완료된
부분(파이프라인+백엔드)을 `[x]`로 옮기고 "프론트엔드는 별도 계획
(`docs/superpowers/plans/2026-08-21-itinerary-frontend.md`)"이라고
메모. `PROGRESS.md`에 오늘 날짜로 무엇을 했는지/왜 이렇게 했는지/막힌
것 섹션을 추가(이 레포의 기존 로그 형식 그대로).

- [ ] **Step 4: 커밋 및 push 여부는 사용자에게 확인**

```bash
git add TODO.md PROGRESS.md
git commit -m "docs: 일정형 영상 백엔드 작업 내역 기록"
```

push는 CLAUDE.md 관례대로 사용자에게 먼저 물어보고 진행한다.
