# 일정 AI 자동 생성 + 수동 편집 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 일정형이 아닌 영상에 대해 버튼 클릭으로 Gemini가 day/순서를 짜주고, 사용자가 모든 일정(파이프라인 판정 + AI 생성)을 직접 수정(다른 날로 이동/순서변경/날짜 추가·삭제)할 수 있게 한다.

**Architecture:** 백엔드는 기존 파이프라인(Python subprocess 호출)과 동일한 패턴으로 새 Python 스크립트(`generate_itinerary.py`)를 통해 Gemini를 호출하는 `ItineraryGenerationService`(@Async)를 추가하고, 기존 `SavedPlace.dayNumber/orderInDay`를 직접 수정하는 `PATCH` 엔드포인트 2개를 `PlacesController`에 추가한다. 새 엔티티는 없다. 프론트는 기존 `/processing/[jobId]` 폴링 페이지를 재사용해 생성 완료를 감지하고, `ItineraryView`에 편집 모드를 추가해 낙관적 업데이트로 각 액션을 즉시 서버에 반영한다.

**Tech Stack:** Spring Boot 4.1.0 / Java 21 (백엔드), Python 3(stdlib만, Gemini REST 직접 호출), Next.js App Router / TypeScript (프론트).

**Spec:** `docs/superpowers/specs/2026-08-22-itinerary-editing-design.md` (commit `067f1e5`)

## Global Constraints

- 유료 API를 기본값으로 제안하지 않는다 — Gemini `gemini-3.5-flash-lite` 무료 티어를 그대로 재사용한다 (신규 모델/API 도입 없음).
- 외부 API 호출이 포함된 처리는 반드시 `@Async`로 처리한다 (트ova-backend CLAUDE.md).
- 새로운 엔티티를 추가하지 않는다 — 기존 `SavedPlace.dayNumber`/`orderInDay`만 재사용한다 (스펙 비목표).
- 날짜 추가/삭제는 백엔드 엔드포인트가 없다 — 프론트 로컬 상태(`emptyDayNumbers`)로만 처리한다 (스펙 "빈 날짜 표현" 섹션).
- 모든 편집 액션은 즉시 서버에 반영한다(낙관적 업데이트 + 실패 시 롤백) — 별도 "저장" 버튼 없음 (스펙 "저장 방식" 섹션).
- 백엔드 커밋 전 `./gradlew build` 실행. 프론트 커밋 전 `npm run lint && npm run build` 실행.
- 커밋 메시지는 `타입: 내용` 형식만, AI 서명/트레일러/이모지 금지 (양쪽 레포 CLAUDE.md 공통).
- 백엔드 작업은 `trova-backend/.worktrees/feat-saved-places-pipeline` (브랜치 `feat/saved-places-pipeline`)에서, 프론트 작업은 `trova-frontend` 레포의 브랜치 `feat/itinerary-view`에서 진행한다.
- 백엔드 테스트는 `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Transactional` 통합 테스트 스타일(기존 `PlacesControllerTest`)을 따르고, 비동기 서비스가 실제 subprocess/Gemini를 호출하지 않도록 `@MockitoBean`으로 대체한다(기존 `SharesControllerTest` 패턴).
- 프론트에는 테스트 러너가 없다(`npm run lint && npm run build`만 존재) — 프론트 태스크의 검증 단계는 lint/build 통과 + 수동 확인이다.

---

### Task 1: Python 일정 생성 스크립트

**Repo/Branch:** trova-backend worktree `feat-saved-places-pipeline`

**Files:**
- Create: `pipeline-test/generate_itinerary.py`

**Interfaces:**
- Consumes: `extract_places.py`의 `call_gemini(parts, model, api_key)`, `load_api_key()`, `_extract_text(payload)`, `DEFAULT_MODEL` (기존 함수, 그대로 import해서 재사용).
- Produces: CLI로 `python3 generate_itinerary.py <places.json>` 실행 시 stdout에 `[{"id": int, "dayNumber": int, "orderInDay": int}, ...]` JSON 배열 출력. 입력 파일 형식: `[{"id": int, "name": str, "region": str, "category": str}, ...]`. Task 3의 `ItineraryPipelineRunner`가 이 입출력 계약을 그대로 사용한다.

- [ ] **Step 1: 스크립트 작성**

`pipeline-test/generate_itinerary.py`:
```python
#!/usr/bin/env python3
"""장소 목록을 받아 Gemini로 며칠짜리 일정(day 배정 + 하루 안 순서)을 짠다.

extract_places.py의 Gemini 호출/재시도 로직을 그대로 재사용한다.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from extract_places import DEFAULT_MODEL, call_gemini, load_api_key, _extract_text

ITINERARY_PROMPT = """당신은 여러 장소를 며칠짜리 여행 일정으로 묶어주는 도구입니다.
아래 "장소 목록"의 각 장소를 하루 단위(day)로 묶고, 하루 안에서 방문 순서를 정하세요.

원칙:
- 같은 지역/동네에 있는 장소는 같은 날에 묶으세요.
- 하루에 방문하기 그럴듯한 개수(보통 2~6곳)로 나누세요. 장소가 아주 적으면 하루로 묶어도 됩니다.
- 방문 순서는 실제 이동 동선이 자연스럽게 이어지도록 정하세요 (카페→식당→관광지처럼 뒤죽박죽 오가지 않게).
- 입력에 있는 장소를 빠짐없이 전부 포함하세요. 장소를 추가하거나 빼지 마세요.

각 항목은 다음 필드를 가집니다:
- id: 입력에 있던 장소의 id를 그대로 반환하세요 (정수)
- dayNumber: 몇 일차인지 (1부터 시작하는 정수)
- orderInDay: 그 날 안에서의 방문 순서 (1부터 시작하는 정수)

JSON 배열만 출력하세요. JSON 배열 외의 다른 텍스트는 출력하지 마세요.
"""


def _validate_assignments(assignments: list, expected_ids: set[int]) -> list:
    result_ids: set[int] = set()
    for item in assignments:
        if not isinstance(item, dict):
            raise SystemExit(f"배열 항목이 객체가 아닙니다: {item!r}")
        place_id = item.get("id")
        day_number = item.get("dayNumber")
        order_in_day = item.get("orderInDay")
        if not isinstance(place_id, int) or isinstance(place_id, bool):
            raise SystemExit(f"id가 정수가 아닙니다: {item!r}")
        if not isinstance(day_number, int) or isinstance(day_number, bool) or day_number < 1:
            raise SystemExit(f"dayNumber가 1 이상의 정수가 아닙니다: {item!r}")
        if not isinstance(order_in_day, int) or isinstance(order_in_day, bool) or order_in_day < 1:
            raise SystemExit(f"orderInDay가 1 이상의 정수가 아닙니다: {item!r}")
        result_ids.add(place_id)
    if result_ids != expected_ids:
        missing = expected_ids - result_ids
        extra = result_ids - expected_ids
        raise SystemExit(f"장소 id가 입력과 일치하지 않습니다 (누락: {missing}, 초과: {extra})")
    return assignments


def generate_itinerary(places: list[dict], model: str = DEFAULT_MODEL) -> list[dict]:
    if not places:
        raise ValueError("places는 비어 있을 수 없습니다")
    api_key = load_api_key()
    parts = [
        {"text": f"장소 목록: {json.dumps(places, ensure_ascii=False)}"},
        {"text": ITINERARY_PROMPT},
    ]
    payload = call_gemini(parts, model, api_key)
    text = _extract_text(payload)
    try:
        assignments = json.loads(text)
    except json.JSONDecodeError:
        raise SystemExit(f"Gemini did not return valid JSON: {text[:500]}")
    if not isinstance(assignments, list):
        raise SystemExit(f"Gemini 응답이 배열이 아닙니다: {text[:500]}")

    expected_ids = {p["id"] for p in places}
    return _validate_assignments(assignments, expected_ids)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: generate_itinerary.py <places.json>", file=sys.stderr)
        raise SystemExit(2)

    input_path = Path(sys.argv[1])
    if not input_path.exists():
        raise SystemExit(f"file not found: {input_path}")

    places = json.loads(input_path.read_text(encoding="utf-8"))
    assignments = generate_itinerary(places)
    print(json.dumps(assignments, ensure_ascii=False, indent=2))
```

- [ ] **Step 2: 로컬 샘플로 수동 검증**

`pipeline-test/.env`에 `GEMINI_API_KEY`가 설정돼 있는지 먼저 확인한다 (`extract_places.py`가 이미 이 파일을 읽으므로 기존 값을 그대로 쓸 수 있다). 샘플 입력을 만들고 실행한다:

```bash
cd pipeline-test
cat > /tmp/sample_places.json <<'EOF'
[
  {"id": 1, "name": "해운대해수욕장", "region": "부산", "category": "attraction"},
  {"id": 2, "name": "웨이브온커피", "region": "부산 해운대", "category": "cafe"},
  {"id": 3, "name": "국제시장", "region": "부산", "category": "attraction"},
  {"id": 4, "name": "부산타워", "region": "부산", "category": "attraction"}
]
EOF
python3 generate_itinerary.py /tmp/sample_places.json
```

Expected: `id` 4개가 전부 포함된 JSON 배열이 출력되고, 각 항목에 `dayNumber`/`orderInDay`가 1 이상의 정수로 채워져 있다. `GEMINI_API_KEY`가 없으면 `GEMINI_API_KEY not set` 에러가 나는데, 이 경우 실제 키를 구해서 재검증하거나(권장) 최소한 `python3 -c "import generate_itinerary"`로 import/문법 오류가 없는지만 확인하고 다음 태스크로 넘어간다 — 이 스크립트의 로직 정확성은 Task 3의 `ItineraryPipelineOutputParserTest`(파싱 계약)로도 부분적으로 커버된다.

- [ ] **Step 3: 커밋**

```bash
git add pipeline-test/generate_itinerary.py
git commit -m "feat: Gemini 기반 일정 자동 생성 스크립트 추가"
```

---

### Task 2: SavedPlace 수정 메서드 + Repository 조회 메서드

**Repo/Branch:** trova-backend worktree `feat-saved-places-pipeline`

**Files:**
- Modify: `src/main/java/com/trova/backend/entity/SavedPlace.java`
- Modify: `src/main/java/com/trova/backend/repository/SavedPlaceRepository.java`

**Interfaces:**
- Produces: `SavedPlace.assignToDay(Integer dayNumber, Integer orderInDay)` — Task 4(`applyItinerary`)와 Task 5(`ItineraryEditService`)가 사용. `SavedPlaceRepository.findByProcessingJob(ProcessingJob job)`, `findByProcessingJobAndDayNumberOrderByOrderInDayAsc(ProcessingJob job, Integer dayNumber)` — Task 4/5가 사용.

- [ ] **Step 1: SavedPlace에 수정 메서드 추가**

`src/main/java/com/trova/backend/entity/SavedPlace.java`의 getter들(`getKakaoPlaceUrl()` 아래) 바로 다음에 추가:

```java
    public void assignToDay(Integer dayNumber, Integer orderInDay) {
        this.dayNumber = dayNumber;
        this.orderInDay = orderInDay;
    }
```

- [ ] **Step 2: SavedPlaceRepository에 조회 메서드 추가**

`src/main/java/com/trova/backend/repository/SavedPlaceRepository.java` 전체를 다음으로 교체:

```java
package com.trova.backend.repository;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedPlaceRepository extends JpaRepository<SavedPlace, Long> {
    List<SavedPlace> findByUserOrderByCreatedAtDescIdDesc(User user);
    Optional<SavedPlace> findByIdAndUser(Long id, User user);
    List<SavedPlace> findByProcessingJob(ProcessingJob processingJob);
    List<SavedPlace> findByProcessingJobAndDayNumberOrderByOrderInDayAsc(ProcessingJob processingJob, Integer dayNumber);
    void deleteByUser(User user);
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL` (아직 이 메서드들을 쓰는 코드가 없으므로 컴파일만 통과하면 됨)

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/trova/backend/entity/SavedPlace.java src/main/java/com/trova/backend/repository/SavedPlaceRepository.java
git commit -m "feat: SavedPlace에 일정 수정용 메서드/조회 쿼리 추가"
```

---

### Task 3: 일정 생성 파이프라인 Java 클래스 (파서 TDD)

**Repo/Branch:** trova-backend worktree `feat-saved-places-pipeline`

**Files:**
- Create: `src/main/java/com/trova/backend/pipeline/ItineraryAssignment.java`
- Create: `src/main/java/com/trova/backend/pipeline/ItineraryPipelineOutputParser.java`
- Create: `src/test/java/com/trova/backend/pipeline/ItineraryPipelineOutputParserTest.java`
- Create: `src/main/java/com/trova/backend/pipeline/ItineraryPipelineRunner.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Consumes: `PipelineException`(기존, `com.trova.backend.pipeline` 패키지), Task 1의 `generate_itinerary.py` 입출력 계약, `com.trova.backend.entity.SavedPlace`(Task 2에서 완성).
- Produces: `ItineraryAssignment(Long id, Integer dayNumber, Integer orderInDay)`, `ItineraryPipelineOutputParser.parse(String stdout): List<ItineraryAssignment>`, `ItineraryPipelineRunner.run(List<SavedPlace> places, Long jobId): List<ItineraryAssignment>` — Task 4가 사용.

- [ ] **Step 1: ItineraryAssignment record 작성**

`src/main/java/com/trova/backend/pipeline/ItineraryAssignment.java`:
```java
package com.trova.backend.pipeline;

public record ItineraryAssignment(Long id, Integer dayNumber, Integer orderInDay) {
}
```

- [ ] **Step 2: 파서 실패 테스트 작성**

`src/test/java/com/trova/backend/pipeline/ItineraryPipelineOutputParserTest.java`:
```java
package com.trova.backend.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItineraryPipelineOutputParserTest {

    @Test
    void 정상_출력을_파싱한다() {
        String stdout = """
                [
                  {"id": 1, "dayNumber": 1, "orderInDay": 1},
                  {"id": 2, "dayNumber": 1, "orderInDay": 2}
                ]
                """;

        List<ItineraryAssignment> assignments = ItineraryPipelineOutputParser.parse(stdout);

        assertThat(assignments).hasSize(2);
        assertThat(assignments.get(0).id()).isEqualTo(1L);
        assertThat(assignments.get(0).dayNumber()).isEqualTo(1);
        assertThat(assignments.get(1).orderInDay()).isEqualTo(2);
    }

    @Test
    void dayNumber가_누락되면_예외를_던진다() {
        String stdout = """
                [{"id": 1, "orderInDay": 1}]
                """;

        assertThrows(PipelineException.class, () -> ItineraryPipelineOutputParser.parse(stdout));
    }

    @Test
    void 일부_항목만_필드가_누락돼도_전체가_실패한다() {
        String stdout = """
                [
                  {"id": 1, "dayNumber": 1, "orderInDay": 1},
                  {"id": 2, "dayNumber": 1}
                ]
                """;

        assertThrows(PipelineException.class, () -> ItineraryPipelineOutputParser.parse(stdout));
    }

    @Test
    void 잘못된_JSON이면_예외를_던진다() {
        assertThrows(PipelineException.class, () -> ItineraryPipelineOutputParser.parse("이건 JSON이 아님"));
    }

    @Test
    void 빈_배열도_정상_파싱된다() {
        List<ItineraryAssignment> assignments = ItineraryPipelineOutputParser.parse("[]");

        assertThat(assignments).isEmpty();
    }
}
```

- [ ] **Step 3: 테스트 실행 → 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.pipeline.ItineraryPipelineOutputParserTest"`
Expected: FAIL (`ItineraryPipelineOutputParser` 클래스가 없어서 컴파일 에러)

- [ ] **Step 4: 파서 구현**

`src/main/java/com/trova/backend/pipeline/ItineraryPipelineOutputParser.java`:
```java
package com.trova.backend.pipeline;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public final class ItineraryPipelineOutputParser {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ItineraryPipelineOutputParser() {
    }

    public static List<ItineraryAssignment> parse(String stdout) {
        List<ItineraryAssignment> assignments;
        try {
            assignments = MAPPER.readValue(
                    stdout,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, ItineraryAssignment.class));
        } catch (Exception e) {
            throw new PipelineException("일정 생성 출력 파싱 실패: " + e.getMessage(), e);
        }

        for (ItineraryAssignment assignment : assignments) {
            if (assignment.id() == null || assignment.dayNumber() == null || assignment.orderInDay() == null) {
                throw new PipelineException("일정 생성 출력에 누락된 필드가 있습니다: " + assignment);
            }
        }
        return assignments;
    }
}
```

- [ ] **Step 5: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.pipeline.ItineraryPipelineOutputParserTest"`
Expected: `BUILD SUCCESSFUL`, 5개 테스트 전부 통과

- [ ] **Step 6: ItineraryPipelineRunner 작성**

`src/main/java/com/trova/backend/pipeline/ItineraryPipelineRunner.java`:
```java
package com.trova.backend.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.SavedPlace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class ItineraryPipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(ItineraryPipelineRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_MINUTES = 2;
    private static final long STDOUT_JOIN_TIMEOUT_MILLIS = 30_000;

    private final String scriptPath;
    private final String workDirBase;
    private final String geminiApiKey;

    public ItineraryPipelineRunner(
            @Value("${app.pipeline.itinerary-script-path}") String scriptPath,
            @Value("${app.pipeline.work-dir}") String workDirBase,
            @Value("${app.pipeline.gemini-api-key}") String geminiApiKey
    ) {
        this.scriptPath = scriptPath;
        this.workDirBase = workDirBase;
        this.geminiApiKey = geminiApiKey;
    }

    public List<ItineraryAssignment> run(List<SavedPlace> places, Long jobId) {
        Path workDir = Path.of(workDirBase, "itinerary-" + jobId);
        Path inputFile = workDir.resolve("places.json");

        try {
            Files.createDirectories(workDir);
            List<Map<String, Object>> payload = places.stream()
                    .map(place -> Map.<String, Object>of(
                            "id", place.getId(),
                            "name", place.getPlaceName(),
                            "region", place.getRegion() == null ? "" : place.getRegion(),
                            "category", place.getCategory() == null ? "" : place.getCategory()
                    ))
                    .toList();
            Files.writeString(inputFile, MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PipelineException("일정 생성 입력 파일 작성 실패: " + e.getMessage(), e);
        }

        ProcessBuilder builder = new ProcessBuilder("python3", scriptPath, inputFile.toString());
        builder.environment().put("GEMINI_API_KEY", geminiApiKey);

        File stderrFile = null;
        try {
            stderrFile = File.createTempFile("trova-itinerary-", ".stderr");
            builder.redirectError(stderrFile);

            Process process = builder.start();

            StringBuilder stdoutBuffer = new StringBuilder();
            Thread stdoutReader = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    stdoutBuffer.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    log.warn("ProcessingJob {} 일정 생성 stdout 읽기 실패", jobId, e);
                }
            }, "itinerary-stdout-" + jobId);
            stdoutReader.setDaemon(true);
            stdoutReader.start();

            boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.error("ProcessingJob {} 일정 생성 실행 시간 초과({}분)", jobId, TIMEOUT_MINUTES);
                throw new PipelineException("일정 생성 실행 시간 초과: jobId=" + jobId);
            }

            stdoutReader.join(STDOUT_JOIN_TIMEOUT_MILLIS);

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String stderr = readStderr(stderrFile);
                log.error("ProcessingJob {} 일정 생성 실행 실패(exit={}): {}", jobId, exitCode, stderr);
                throw new PipelineException("일정 생성 실행 실패(exit=" + exitCode + "): " + stderr);
            }

            return ItineraryPipelineOutputParser.parse(stdoutBuffer.toString());
        } catch (IOException e) {
            throw new PipelineException("일정 생성 프로세스 시작 실패: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException("일정 생성 실행 중 인터럽트: " + e.getMessage(), e);
        } finally {
            if (stderrFile != null && !stderrFile.delete()) {
                log.warn("일정 생성 stderr 임시 파일 삭제 실패: {}", stderrFile.getAbsolutePath());
            }
        }
    }

    private String readStderr(File stderrFile) {
        try {
            return Files.readString(stderrFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(stderr 읽기 실패: " + e.getMessage() + ")";
        }
    }
}
```

이 클래스는 기존 `PipelineRunner`와 마찬가지로 subprocess/파일시스템 의존이라 단위테스트를 따로 두지 않는다(기존 `PipelineRunner`도 테스트 파일이 없음 — 동일 컨벤션).

- [ ] **Step 7: application.yml에 스크립트 경로 추가**

`src/main/resources/application.yml`의 `app.pipeline` 아래에 `script-path` 다음 줄로 추가:
```yaml
  pipeline:
    script-path: ${PIPELINE_SCRIPT_PATH:pipeline-test/run_pipeline.py}
    itinerary-script-path: ${ITINERARY_SCRIPT_PATH:pipeline-test/generate_itinerary.py}
    work-dir: ${PIPELINE_WORK_DIR:pipeline-test/work}
    gemini-api-key: ${GEMINI_API_KEY:}
```

- [ ] **Step 8: 전체 빌드 확인**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/trova/backend/pipeline/ItineraryAssignment.java \
        src/main/java/com/trova/backend/pipeline/ItineraryPipelineOutputParser.java \
        src/test/java/com/trova/backend/pipeline/ItineraryPipelineOutputParserTest.java \
        src/main/java/com/trova/backend/pipeline/ItineraryPipelineRunner.java \
        src/main/resources/application.yml
git commit -m "feat: 일정 생성 파이프라인 실행/파싱 클래스 추가"
```

---

### Task 4: applyItinerary + ItineraryGenerationService

**Repo/Branch:** trova-backend worktree `feat-saved-places-pipeline`

**Files:**
- Modify: `src/main/java/com/trova/backend/service/ProcessingJobLifecycleService.java`
- Modify: `src/test/java/com/trova/backend/service/ProcessingJobLifecycleServiceIntegrationTest.java`
- Create: `src/main/java/com/trova/backend/service/ItineraryGenerationService.java`

**Interfaces:**
- Consumes: Task 2의 `SavedPlace.assignToDay`, `SavedPlaceRepository.findByProcessingJob`; Task 3의 `ItineraryAssignment`, `ItineraryPipelineRunner.run`; 기존 `ProcessingJobLifecycleService.markProcessing/markDone/markFailed`.
- Produces: `ProcessingJobLifecycleService.applyItinerary(Long jobId, List<ItineraryAssignment> assignments): void` — Task 5(컨트롤러)는 직접 호출하지 않고 `ItineraryGenerationService.generate(Long jobId): void`(`@Async`)를 통해서만 트리거한다.

- [ ] **Step 1: applyItinerary 실패 테스트 작성**

`src/test/java/com/trova/backend/service/ProcessingJobLifecycleServiceIntegrationTest.java`의 import 목록에 추가:
```java
import com.trova.backend.pipeline.ItineraryAssignment;
```

마지막 `@Test` 메서드 다음(클래스 닫는 `}` 바로 앞)에 추가:
```java
    @Test
    void applyItinerary는_id로_매칭되는_장소들의_day_order를_갱신한다() {
        ProcessingJob job = newJob();
        SavedPlace first = savedPlaceRepository.save(
                new SavedPlace(job, job.getUser(), "첫 장소", "부산", "cafe", 35.1, 129.0));
        SavedPlace second = savedPlaceRepository.save(
                new SavedPlace(job, job.getUser(), "두번째 장소", "부산", "cafe", 35.2, 129.1));

        lifecycleService.applyItinerary(job.getId(), List.of(
                new ItineraryAssignment(first.getId(), 1, 1),
                new ItineraryAssignment(second.getId(), 1, 2)
        ));

        SavedPlace updatedFirst = savedPlaceRepository.findById(first.getId()).orElseThrow();
        SavedPlace updatedSecond = savedPlaceRepository.findById(second.getId()).orElseThrow();
        assertThat(updatedFirst.getDayNumber()).isEqualTo(1);
        assertThat(updatedFirst.getOrderInDay()).isEqualTo(1);
        assertThat(updatedSecond.getOrderInDay()).isEqualTo(2);
    }
```

(`newJob()`은 기존 헬퍼로, `ProcessingJob`을 저장해 반환한다 — `ProcessingJob.getUser()`는 기존 엔티티의 getter이므로 그대로 사용 가능하다.)

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.service.ProcessingJobLifecycleServiceIntegrationTest"`
Expected: FAIL (`applyItinerary` 메서드가 없어서 컴파일 에러)

- [ ] **Step 3: applyItinerary 구현**

`src/main/java/com/trova/backend/service/ProcessingJobLifecycleService.java`에 import 추가:
```java
import com.trova.backend.pipeline.ItineraryAssignment;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
```

`markDone` 메서드 다음에 추가:
```java
    @Transactional
    public void applyItinerary(Long jobId, List<ItineraryAssignment> assignments) {
        ProcessingJob job = getJob(jobId);
        Map<Long, SavedPlace> byId = savedPlaceRepository.findByProcessingJob(job).stream()
                .collect(Collectors.toMap(SavedPlace::getId, place -> place));
        for (ItineraryAssignment assignment : assignments) {
            SavedPlace place = byId.get(assignment.id());
            if (place == null) {
                continue;
            }
            place.assignToDay(assignment.dayNumber(), assignment.orderInDay());
        }
    }
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.service.ProcessingJobLifecycleServiceIntegrationTest"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: ItineraryGenerationService 작성**

`src/main/java/com/trova/backend/service/ItineraryGenerationService.java`:
```java
package com.trova.backend.service;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.pipeline.ItineraryAssignment;
import com.trova.backend.pipeline.ItineraryPipelineRunner;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ItineraryGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ItineraryGenerationService.class);

    private final ProcessingJobLifecycleService lifecycleService;
    private final ItineraryPipelineRunner itineraryPipelineRunner;
    private final ProcessingJobRepository processingJobRepository;
    private final SavedPlaceRepository savedPlaceRepository;

    public ItineraryGenerationService(
            ProcessingJobLifecycleService lifecycleService,
            ItineraryPipelineRunner itineraryPipelineRunner,
            ProcessingJobRepository processingJobRepository,
            SavedPlaceRepository savedPlaceRepository
    ) {
        this.lifecycleService = lifecycleService;
        this.itineraryPipelineRunner = itineraryPipelineRunner;
        this.processingJobRepository = processingJobRepository;
        this.savedPlaceRepository = savedPlaceRepository;
    }

    @Async("pipelineTaskExecutor")
    public void generate(Long jobId) {
        try {
            lifecycleService.markProcessing(jobId);
            ProcessingJob job = processingJobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("ProcessingJob을 찾을 수 없습니다: " + jobId));
            List<SavedPlace> places = savedPlaceRepository.findByProcessingJob(job);

            List<ItineraryAssignment> assignments = itineraryPipelineRunner.run(places, jobId);
            lifecycleService.applyItinerary(jobId, assignments);

            lifecycleService.markDone(jobId);
            log.info("ProcessingJob {} 일정 생성 완료: {}개 장소", jobId, assignments.size());
        } catch (Exception e) {
            log.error("ProcessingJob {} 일정 생성 실패", jobId, e);
            lifecycleService.markFailed(jobId, e.getMessage());
        }
    }
}
```

(`PlaceExtractionService`와 동일하게 이 orchestrator 클래스도 subprocess 의존 때문에 별도 단위테스트를 두지 않는다 — 기존 `PlaceExtractionService`도 테스트 파일이 없음. 컨트롤러 레이어에서는 Task 5의 `@MockitoBean`으로 대체해서 검증한다.)

- [ ] **Step 6: 전체 빌드 확인**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/trova/backend/service/ProcessingJobLifecycleService.java \
        src/test/java/com/trova/backend/service/ProcessingJobLifecycleServiceIntegrationTest.java \
        src/main/java/com/trova/backend/service/ItineraryGenerationService.java
git commit -m "feat: 일정 생성 결과를 SavedPlace에 반영하는 서비스 추가"
```

---

### Task 5: PlacesController — 일정 생성 트리거 + 편집(day/order) 엔드포인트

**Repo/Branch:** trova-backend worktree `feat-saved-places-pipeline`

**Files:**
- Create: `src/main/java/com/trova/backend/service/ItineraryEditService.java`
- Modify: `src/main/java/com/trova/backend/controller/PlacesController.java`
- Modify: `src/test/java/com/trova/backend/controller/PlacesControllerTest.java`

**Interfaces:**
- Consumes: Task 2의 `SavedPlaceRepository.findByProcessingJobAndDayNumberOrderByOrderInDayAsc`, `SavedPlace.assignToDay`; Task 4의 `ItineraryGenerationService.generate(Long jobId)`.
- Produces: `PlaceResponse`에 `jobId` 필드 추가(프론트 Task 6이 사용). `POST /api/places/videos/{jobId}/itinerary` (202/404), `PATCH /api/places/{id}/day` (200/400/404), `PATCH /api/places/{id}/order` (200/400/404) — 프론트 Task 6의 `generateItinerary`/`moveToDay`/`reorderPlace`가 호출.

- [ ] **Step 1: ItineraryEditService 작성**

`src/main/java/com/trova/backend/service/ItineraryEditService.java`:
```java
package com.trova.backend.service;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.repository.SavedPlaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ItineraryEditService {

    private final SavedPlaceRepository savedPlaceRepository;

    public ItineraryEditService(SavedPlaceRepository savedPlaceRepository) {
        this.savedPlaceRepository = savedPlaceRepository;
    }

    @Transactional
    public SavedPlace moveToDay(SavedPlace place, Integer dayNumber) {
        ProcessingJob job = place.getProcessingJob();
        List<SavedPlace> siblings =
                savedPlaceRepository.findByProcessingJobAndDayNumberOrderByOrderInDayAsc(job, dayNumber);
        int nextOrder = siblings.isEmpty() ? 1 : siblings.get(siblings.size() - 1).getOrderInDay() + 1;
        place.assignToDay(dayNumber, nextOrder);
        return place;
    }

    @Transactional
    public SavedPlace reorder(SavedPlace place, String direction) {
        if (place.getDayNumber() == null) {
            return place;
        }
        List<SavedPlace> siblings = savedPlaceRepository
                .findByProcessingJobAndDayNumberOrderByOrderInDayAsc(place.getProcessingJob(), place.getDayNumber());

        int index = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(place.getId())) {
                index = i;
                break;
            }
        }
        int swapIndex = "UP".equals(direction) ? index - 1 : index + 1;
        if (index < 0 || swapIndex < 0 || swapIndex >= siblings.size()) {
            return place; // 경계값(맨 위/맨 아래) — no-op
        }

        SavedPlace neighbor = siblings.get(swapIndex);
        int placeOrder = place.getOrderInDay();
        int neighborOrder = neighbor.getOrderInDay();
        place.assignToDay(place.getDayNumber(), neighborOrder);
        neighbor.assignToDay(neighbor.getDayNumber(), placeOrder);
        return place;
    }
}
```

- [ ] **Step 2: PlacesController 실패 테스트 작성**

`src/test/java/com/trova/backend/controller/PlacesControllerTest.java`의 import 목록에 추가:
```java
import com.trova.backend.service.ItineraryGenerationService;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
```

클래스 필드에 추가(`@Autowired private SavedPlaceRepository savedPlaceRepository;` 다음):
```java
    @MockitoBean
    private ItineraryGenerationService itineraryGenerationService;
```

마지막 `@Test` 메서드 다음(클래스 닫는 `}` 바로 앞)에 추가:
```java
    @Test
    void 장소를_다른_날로_옮기면_해당_날_맨_뒤에_배정된다() throws Exception {
        User me = userRepository.save(new User("google", "day1", "일정편집1", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/day1", SourcePlatform.YOUTUBE));
        savedPlaceRepository.save(new SavedPlace(job, me, "1일차 장소", "부산", "cafe", 35.1, 129.0, 1, 1));
        SavedPlace moving = savedPlaceRepository.save(new SavedPlace(job, me, "옮길 장소", "부산", "cafe", 35.2, 129.1, 2, 1));

        mockMvc.perform(patch("/api/places/" + moving.getId() + "/day")
                        .with(loginAs("day1", "일정편집1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayNumber\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dayNumber").value(1))
                .andExpect(jsonPath("$.orderInDay").value(2));
    }

    @Test
    void 타인_소유_장소를_다른_날로_옮기려_하면_404() throws Exception {
        User me = userRepository.save(new User("google", "day2", "일정편집2", null));
        User other = userRepository.save(new User("google", "day3", "일정편집3", null));
        ProcessingJob otherJob = processingJobRepository.save(new ProcessingJob(other, "https://youtu.be/day2", SourcePlatform.YOUTUBE));
        SavedPlace otherPlace = savedPlaceRepository.save(new SavedPlace(otherJob, other, "남의 장소", "부산", "cafe", 35.1, 129.0, 1, 1));

        mockMvc.perform(patch("/api/places/" + otherPlace.getId() + "/day")
                        .with(loginAs("day2", "일정편집2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayNumber\": 1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void dayNumber가_1보다_작으면_400() throws Exception {
        User me = userRepository.save(new User("google", "day4", "일정편집4", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/day4", SourcePlatform.YOUTUBE));
        SavedPlace place = savedPlaceRepository.save(new SavedPlace(job, me, "장소", "부산", "cafe", 35.1, 129.0, 1, 1));

        mockMvc.perform(patch("/api/places/" + place.getId() + "/day")
                        .with(loginAs("day4", "일정편집4"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayNumber\": 0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 같은_날_안에서_아래로_순서를_바꾸면_인접한_장소와_교체된다() throws Exception {
        User me = userRepository.save(new User("google", "order1", "순서1", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/order1", SourcePlatform.YOUTUBE));
        SavedPlace first = savedPlaceRepository.save(new SavedPlace(job, me, "첫번째", "부산", "cafe", 35.1, 129.0, 1, 1));
        SavedPlace second = savedPlaceRepository.save(new SavedPlace(job, me, "두번째", "부산", "cafe", 35.2, 129.1, 1, 2));

        mockMvc.perform(patch("/api/places/" + first.getId() + "/order")
                        .with(loginAs("order1", "순서1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\": \"DOWN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderInDay").value(2));

        org.assertj.core.api.Assertions.assertThat(
                        savedPlaceRepository.findById(second.getId()).orElseThrow().getOrderInDay())
                .isEqualTo(1);
    }

    @Test
    void 맨_위에서_위로_순서를_바꾸면_아무_변화_없다() throws Exception {
        User me = userRepository.save(new User("google", "order2", "순서2", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/order2", SourcePlatform.YOUTUBE));
        SavedPlace first = savedPlaceRepository.save(new SavedPlace(job, me, "첫번째", "부산", "cafe", 35.1, 129.0, 1, 1));

        mockMvc.perform(patch("/api/places/" + first.getId() + "/order")
                        .with(loginAs("order2", "순서2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\": \"UP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderInDay").value(1));
    }

    @Test
    void direction이_UP_DOWN이_아니면_400() throws Exception {
        User me = userRepository.save(new User("google", "order3", "순서3", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/order3", SourcePlatform.YOUTUBE));
        SavedPlace place = savedPlaceRepository.save(new SavedPlace(job, me, "장소", "부산", "cafe", 35.1, 129.0, 1, 1));

        mockMvc.perform(patch("/api/places/" + place.getId() + "/order")
                        .with(loginAs("order3", "순서3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\": \"SIDEWAYS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 일정_생성_요청은_202를_반환하고_비동기_서비스를_호출한다() throws Exception {
        User me = userRepository.save(new User("google", "gen1", "생성1", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/gen1", SourcePlatform.YOUTUBE));
        savedPlaceRepository.save(new SavedPlace(job, me, "장소", "부산", "cafe", 35.1, 129.0));
        doNothing().when(itineraryGenerationService).generate(anyLong());

        mockMvc.perform(post("/api/places/videos/" + job.getId() + "/itinerary")
                        .with(loginAs("gen1", "생성1")))
                .andExpect(status().isAccepted());

        verify(itineraryGenerationService).generate(job.getId());
    }

    @Test
    void 타인_소유_영상의_일정_생성_요청은_404() throws Exception {
        User me = userRepository.save(new User("google", "gen2", "생성2", null));
        User other = userRepository.save(new User("google", "gen3", "생성3", null));
        ProcessingJob otherJob = processingJobRepository.save(new ProcessingJob(other, "https://youtu.be/gen2", SourcePlatform.YOUTUBE));

        mockMvc.perform(post("/api/places/videos/" + otherJob.getId() + "/itinerary")
                        .with(loginAs("gen2", "생성2")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 장소_목록_응답에_jobId가_포함된다() throws Exception {
        User me = userRepository.save(new User("google", "jobid1", "jobId유저", null));
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(me, "https://youtu.be/jobid1", SourcePlatform.YOUTUBE));
        savedPlaceRepository.save(new SavedPlace(job, me, "장소", "부산", "cafe", 35.1, 129.0));

        mockMvc.perform(get("/api/places").with(loginAs("jobid1", "jobId유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").value(job.getId()));
    }
```

- [ ] **Step 3: 테스트 실행 → 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.controller.PlacesControllerTest"`
Expected: FAIL (엔드포인트/필드가 없어서 컴파일 에러 또는 404)

- [ ] **Step 4: PlacesController 구현**

`src/main/java/com/trova/backend/controller/PlacesController.java` 전체를 다음으로 교체:
```java
package com.trova.backend.controller;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.ItineraryEditService;
import com.trova.backend.service.ItineraryGenerationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/places")
public class PlacesController {

    private static final Set<String> VALID_DIRECTIONS = Set.of("UP", "DOWN");

    private final CurrentUserService currentUserService;
    private final SavedPlaceRepository savedPlaceRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final ItineraryGenerationService itineraryGenerationService;
    private final ItineraryEditService itineraryEditService;

    public PlacesController(
            CurrentUserService currentUserService,
            SavedPlaceRepository savedPlaceRepository,
            ProcessingJobRepository processingJobRepository,
            ItineraryGenerationService itineraryGenerationService,
            ItineraryEditService itineraryEditService
    ) {
        this.currentUserService = currentUserService;
        this.savedPlaceRepository = savedPlaceRepository;
        this.processingJobRepository = processingJobRepository;
        this.itineraryGenerationService = itineraryGenerationService;
        this.itineraryEditService = itineraryEditService;
    }

    public record PlaceResponse(
            Long id, Long jobId, String placeName, String region, String category,
            Double latitude, Double longitude, String sourceUrl, String title,
            String sourcePlatform, String createdAt, Integer dayNumber, Integer orderInDay,
            String phone, String address, String roadAddress,
            String kakaoCategoryName, String kakaoPlaceUrl
    ) {
        static PlaceResponse from(SavedPlace place) {
            return new PlaceResponse(
                    place.getId(), place.getProcessingJob().getId(), place.getPlaceName(), place.getRegion(),
                    place.getCategory(), place.getLatitude(), place.getLongitude(), place.getSourceUrl(),
                    place.getTitle(), place.getSourcePlatform().name(), place.getCreatedAt().toString(),
                    place.getDayNumber(), place.getOrderInDay(),
                    place.getPhone(), place.getAddress(), place.getRoadAddress(),
                    place.getKakaoCategoryName(), place.getKakaoPlaceUrl()
            );
        }
    }

    public record PendingJobResponse(
            Long jobId, String sourceUrl, String title, String sourcePlatform, String status, String createdAt
    ) {
        static PendingJobResponse from(ProcessingJob job) {
            return new PendingJobResponse(
                    job.getId(), job.getSourceUrl(), job.getTitle(), job.getSourcePlatform().name(),
                    job.getStatus().name(), job.getCreatedAt().toString()
            );
        }
    }

    public record MoveDayRequest(Integer dayNumber) {
    }

    public record ReorderRequest(String direction) {
    }

    @GetMapping
    public List<PlaceResponse> list(OAuth2AuthenticationToken authentication) {
        User user = currentUserService.resolve(authentication);
        return savedPlaceRepository.findByUserOrderByCreatedAtDescIdDesc(user).stream()
                .map(PlaceResponse::from)
                .toList();
    }

    @GetMapping("/pending")
    public List<PendingJobResponse> pending(OAuth2AuthenticationToken authentication) {
        User user = currentUserService.resolve(authentication);
        // FAILED도 포함한다 — 실패한 job은 SavedPlace가 안 생겨서, 여기서 빼면
        // 사용자 입장에서 요청이 이유 없이 사라진 것처럼 보인다.
        return processingJobRepository.findByUserAndStatusIn(
                        user, List.of(JobStatus.PENDING, JobStatus.PROCESSING, JobStatus.FAILED))
                .stream()
                .map(PendingJobResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<PlaceResponse> get(OAuth2AuthenticationToken authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        return savedPlaceRepository.findByIdAndUser(id, user)
                .map(place -> ResponseEntity.ok(PlaceResponse.from(place)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(OAuth2AuthenticationToken authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        return savedPlaceRepository.findByIdAndUser(id, user)
                .map(place -> {
                    savedPlaceRepository.delete(place);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/day")
    public ResponseEntity<PlaceResponse> moveDay(
            OAuth2AuthenticationToken authentication, @PathVariable Long id, @RequestBody MoveDayRequest body
    ) {
        if (body == null || body.dayNumber() == null || body.dayNumber() < 1) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        return savedPlaceRepository.findByIdAndUser(id, user)
                .map(place -> ResponseEntity.ok(
                        PlaceResponse.from(itineraryEditService.moveToDay(place, body.dayNumber()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/order")
    public ResponseEntity<PlaceResponse> reorder(
            OAuth2AuthenticationToken authentication, @PathVariable Long id, @RequestBody ReorderRequest body
    ) {
        if (body == null || !VALID_DIRECTIONS.contains(body.direction())) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        return savedPlaceRepository.findByIdAndUser(id, user)
                .map(place -> ResponseEntity.ok(
                        PlaceResponse.from(itineraryEditService.reorder(place, body.direction()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/videos/{jobId}/itinerary")
    public ResponseEntity<Void> generateItinerary(OAuth2AuthenticationToken authentication, @PathVariable Long jobId) {
        User user = currentUserService.resolve(authentication);
        return processingJobRepository.findById(jobId)
                .filter(job -> job.getUser().getId().equals(user.getId()))
                .map(job -> {
                    itineraryGenerationService.generate(jobId);
                    return ResponseEntity.accepted().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 5: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.controller.PlacesControllerTest"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 전체 빌드 확인**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/trova/backend/service/ItineraryEditService.java \
        src/main/java/com/trova/backend/controller/PlacesController.java \
        src/test/java/com/trova/backend/controller/PlacesControllerTest.java
git commit -m "feat: 일정 생성 트리거 및 day/순서 편집 엔드포인트 추가"
```

이 태스크로 백엔드 작업이 끝난다. 이 시점부터 프론트 작업(Task 6-8)은 백엔드 API 계약(`PlaceResponse.jobId`, `PATCH /{id}/day`, `PATCH /{id}/order`, `POST /videos/{jobId}/itinerary`)에 맞춰 진행하되, 로컬 백엔드 서버(`./gradlew bootRun`)가 떠 있어야 수동 확인이 가능하다.

---

### Task 6: 프론트 — 타입/API 클라이언트 + getPlaces 중복 방지

**Repo/Branch:** trova-frontend, 브랜치 `feat/itinerary-view`

**Files:**
- Modify: `src/lib/types.ts`
- Modify: `src/lib/api/places.ts`

**Interfaces:**
- Consumes: Task 5의 `PlaceResponse`(`jobId` 포함), `PATCH /{id}/day`, `PATCH /{id}/order`, `POST /videos/{jobId}/itinerary`.
- Produces: `SavedPlace.jobId: number`, `generateItinerary(jobId: number): Promise<void>`, `moveToDay(placeId: string, dayNumber: number): Promise<SavedPlace>`, `reorderPlace(placeId: string, direction: "UP" | "DOWN"): Promise<SavedPlace>` — Task 7/8이 사용.

- [ ] **Step 1: SavedPlace 타입에 jobId 추가**

`src/lib/types.ts`의 `id: string;` 다음 줄에 추가:
```ts
  jobId: number;
```

- [ ] **Step 2: PlaceResponse/PendingJobResponse 타입에 jobId 반영**

`src/lib/api/places.ts`의 `PlaceResponse` 타입에서 `id: number;` 다음 줄에 추가:
```ts
  jobId: number;
```

`fromPlaceResponse`의 반환 객체에서 `id: String(place.id),` 다음 줄에 추가:
```ts
    jobId: place.jobId,
```

`fromPendingJobResponse`의 반환 객체에서 `id: String(job.jobId),` 다음 줄에 추가:
```ts
    jobId: job.jobId,
```

- [ ] **Step 3: getPlaces()에 pending 중복 방지 필터 추가**

`getPlaces()` 함수 전체를 다음으로 교체:
```ts
export async function getPlaces(): Promise<SavedPlace[]> {
  const [placesRes, pending] = await Promise.all([
    fetch(`${API_BASE_URL}/api/places`, { credentials: "include" }),
    getPendingJobs(),
  ]);

  if (!placesRes.ok) {
    throw new Error(`GET /api/places failed: ${placesRes.status}`);
  }
  const places: PlaceResponse[] = await placesRes.json();

  // 이미 완료된 영상(DONE 장소가 있는 sourceUrl)의 job이 재처리(예: 일정 생성 트리거)로
  // 다시 PENDING/PROCESSING/FAILED에 나타나면, 완료된 장소 목록과 중복 표시되는 걸 막는다.
  const doneSourceUrls = new Set(places.map((place) => place.sourceUrl));
  const activePending = pending.filter((job) => !doneSourceUrls.has(job.sourceUrl));

  return [...activePending.map(fromPendingJobResponse), ...places.map(fromPlaceResponse)].sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
  );
}
```

- [ ] **Step 4: 일정 편집용 API 함수 추가**

파일 끝(`deletePlace` 함수 다음)에 추가:
```ts
export async function generateItinerary(jobId: number): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/api/places/videos/${jobId}/itinerary`, {
    method: "POST",
    credentials: "include",
  });

  if (!res.ok) {
    throw new Error(`POST /api/places/videos/${jobId}/itinerary failed: ${res.status}`);
  }
}

export async function moveToDay(placeId: string, dayNumber: number): Promise<SavedPlace> {
  const res = await fetch(`${API_BASE_URL}/api/places/${placeId}/day`, {
    method: "PATCH",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ dayNumber }),
  });

  if (!res.ok) {
    throw new Error(`PATCH /api/places/${placeId}/day failed: ${res.status}`);
  }
  return fromPlaceResponse(await res.json());
}

export async function reorderPlace(
  placeId: string,
  direction: "UP" | "DOWN"
): Promise<SavedPlace> {
  const res = await fetch(`${API_BASE_URL}/api/places/${placeId}/order`, {
    method: "PATCH",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ direction }),
  });

  if (!res.ok) {
    throw new Error(`PATCH /api/places/${placeId}/order failed: ${res.status}`);
  }
  return fromPlaceResponse(await res.json());
}
```

- [ ] **Step 5: lint/build 확인**

Run: `npm run lint && npm run build`
Expected: 에러 없이 통과 (아직 이 함수들을 쓰는 컴포넌트가 없어도 export만으로는 에러가 나지 않는다)

- [ ] **Step 6: 커밋**

```bash
git add src/lib/types.ts src/lib/api/places.ts
git commit -m "feat: 일정 편집 API 클라이언트 및 jobId 필드 추가"
```

---

### Task 7: 프론트 — 상세 페이지 "일정 짜기" 버튼

**Repo/Branch:** trova-frontend, 브랜치 `feat/itinerary-view`

**Files:**
- Modify: `src/app/places/[id]/page.tsx`

**Interfaces:**
- Consumes: Task 6의 `generateItinerary(jobId)`, 기존 `isItineraryGroup(group)`, 기존 `/processing/[jobId]` 페이지(변경 없음, 그대로 재사용).

- [ ] **Step 1: import 및 상태 추가**

`src/app/places/[id]/page.tsx` 상단 import에 추가:
```tsx
import { useParams, useRouter } from "next/navigation";
```
(기존 `import { useParams } from "next/navigation";` 줄을 이걸로 교체)

```tsx
import { generateItinerary, getPlaces } from "@/lib/api/places";
```
(기존 `import { getPlaces } from "@/lib/api/places";` 줄을 이걸로 교체)

컴포넌트 안, `const [dataLoading, setDataLoading] = useState(true);` 다음 줄에 추가:
```tsx
  const router = useRouter();
  const [generating, setGenerating] = useState(false);
  const [generateError, setGenerateError] = useState<string | null>(null);
```

- [ ] **Step 2: 생성 트리거 핸들러 추가**

`const loading = ...` 줄 앞에 추가:
```tsx
  const canGenerateItinerary =
    group.length > 0 && group.every((place) => place.status === "DONE") && !isItineraryGroup(group);

  async function handleGenerateItinerary() {
    if (group.length === 0) return;
    setGenerating(true);
    setGenerateError(null);
    try {
      await generateItinerary(group[0].jobId);
      router.push(`/processing/${group[0].jobId}`);
    } catch {
      setGenerateError("일정 생성 요청에 실패했어요. 다시 시도해주세요.");
      setGenerating(false);
    }
  }
```

(주의: `group`은 이 시점 아래 JSX보다 먼저 선언돼 있어야 한다 — 기존 코드에서 `const group = places.filter(...)`가 `const loading = ...` 다음 줄에 있으므로, 이 Step의 코드는 `group` 선언 다음, `loading` 선언 이후에 위치시킨다.)

- [ ] **Step 3: 버튼 렌더링**

"원본 영상 보기 ↗" `<a>` 태그 다음, `{isItineraryGroup(group) ? (...) : (...)}` 블록 앞에 추가:
```tsx
          {canGenerateItinerary && (
            <div className="mb-4">
              <button
                type="button"
                onClick={handleGenerateItinerary}
                disabled={generating}
                className="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-60"
              >
                {generating ? "일정 생성 중..." : "일정 짜기"}
              </button>
              {generateError && <p className="mt-2 text-sm text-accent">{generateError}</p>}
            </div>
          )}
```

- [ ] **Step 4: lint/build 확인**

Run: `npm run lint && npm run build`
Expected: 에러 없이 통과

- [ ] **Step 5: 수동 확인**

로컬 백엔드(`./gradlew bootRun`, worktree `feat-saved-places-pipeline`)와 프론트(`npm run dev`)를 함께 띄운다. 일정형이 아닌 완료된 영상의 상세 페이지에 들어가서:
1. "일정 짜기" 버튼이 보이는지 확인
2. 클릭 시 `/processing/{jobId}`로 이동하고 "AI가 영상을 분석하고 있어요" 문구가 보이는지 확인(재사용 페이지라 문구가 STT 분석 문구 그대로인 점은 알려진 사소한 차이 — 이번 태스크 범위 밖)
3. 생성이 끝나면 자동으로 상세 페이지로 돌아오고, `ItineraryView`(일자 탭)가 뜨는지 확인 (Task 8 완료 전까지는 기존 읽기 전용 `ItineraryView`가 뜬다)

- [ ] **Step 6: 커밋**

```bash
git add src/app/places/[id]/page.tsx
git commit -m "feat: 상세 페이지에 일정 생성 버튼 추가"
```

---

### Task 8: 프론트 — ItineraryView 편집 모드

**Repo/Branch:** trova-frontend, 브랜치 `feat/itinerary-view`

**Files:**
- Modify: `src/components/PlaceMapSection.tsx`
- Modify: `src/components/ItineraryView.tsx`

**Interfaces:**
- Consumes: Task 6의 `moveToDay(placeId, dayNumber)`, `reorderPlace(placeId, direction)`; 기존 `groupByDay`, `PlaceCard`, `KakaoMap`.

- [ ] **Step 1: PlaceMapSection에 편집 props 추가**

`src/components/PlaceMapSection.tsx` 전체를 다음으로 교체:
```tsx
"use client";

import { Fragment, useState } from "react";
import type { SavedPlace } from "@/lib/types";
import { KakaoMap } from "@/components/KakaoMap";
import { PlaceCard } from "@/components/PlaceCard";

type PlaceMapSectionProps = {
  places: SavedPlace[];
  editable?: boolean;
  availableDayNumbers?: number[];
  onMoveDay?: (place: SavedPlace, dayNumber: number) => void;
  onReorder?: (place: SavedPlace, direction: "UP" | "DOWN") => void;
};

export function PlaceMapSection({
  places,
  editable = false,
  availableDayNumbers = [],
  onMoveDay,
  onReorder,
}: PlaceMapSectionProps) {
  const [selectedId, setSelectedId] = useState<string | null>(null);

  return (
    <div className="flex flex-col gap-4">
      <KakaoMap
        pins={places.map((place) => ({
          id: place.id,
          latitude: place.latitude,
          longitude: place.longitude,
        }))}
        selectedId={selectedId}
        onSelect={setSelectedId}
      />
      <p className="text-xs text-ink-muted">
        핀은 영상에 나온 순서대로 직선으로 이었어요 — 실제 이동 경로는 아니에요.
      </p>

      <ul className="flex flex-col gap-3">
        {places.map((place, index) => (
          <Fragment key={place.id}>
            <PlaceCard
              place={place}
              selected={place.id === selectedId}
              onClick={() => setSelectedId(place.id)}
            />
            {editable && (
              <li className="-mt-2 flex list-none items-center gap-3 pl-1">
                {onReorder && (
                  <div className="flex gap-1">
                    <button
                      type="button"
                      disabled={index === 0}
                      onClick={() => onReorder(place, "UP")}
                      className="rounded border border-border-subtle px-2 py-0.5 text-xs text-ink-muted hover:text-ink disabled:opacity-30"
                    >
                      ↑
                    </button>
                    <button
                      type="button"
                      disabled={index === places.length - 1}
                      onClick={() => onReorder(place, "DOWN")}
                      className="rounded border border-border-subtle px-2 py-0.5 text-xs text-ink-muted hover:text-ink disabled:opacity-30"
                    >
                      ↓
                    </button>
                  </div>
                )}
                {onMoveDay && availableDayNumbers.length > 0 && (
                  <select
                    value={place.dayNumber ?? ""}
                    onChange={(e) => onMoveDay(place, Number(e.target.value))}
                    className="rounded border border-border-subtle bg-bg px-2 py-0.5 text-xs text-ink-muted"
                  >
                    <option value="" disabled>
                      날짜 선택
                    </option>
                    {availableDayNumbers.map((day) => (
                      <option key={day} value={day}>
                        {day}일차
                      </option>
                    ))}
                  </select>
                )}
              </li>
            )}
          </Fragment>
        ))}
      </ul>
    </div>
  );
}
```

- [ ] **Step 2: ItineraryView에 편집 모드 추가**

`src/components/ItineraryView.tsx` 전체를 다음으로 교체:
```tsx
"use client";

import { useState } from "react";
import type { SavedPlace } from "@/lib/types";
import { groupByDay } from "@/lib/itinerary";
import { PlaceMapSection } from "@/components/PlaceMapSection";
import { moveToDay, reorderPlace } from "@/lib/api/places";

export function ItineraryView({ places }: { places: SavedPlace[] }) {
  const [localPlaces, setLocalPlaces] = useState(places);
  const [editing, setEditing] = useState(false);
  const [emptyDayNumbers, setEmptyDayNumbers] = useState<number[]>([]);
  const [error, setError] = useState<string | null>(null);

  const days = groupByDay(localPlaces);
  const dayNumbers = Array.from(new Set([...days.keys(), ...emptyDayNumbers])).sort((a, b) => a - b);
  // 호출 측(page.tsx)이 isItineraryGroup()로 걸러서 dayNumbers가 비지 않는 그룹만 넘겨준다는
  // 전제 + key={sourceUrl}로 그룹이 바뀔 때마다 이 컴포넌트가 새로 마운트된다는 전제 위에서
  // activeDay를 한 번만 초기화한다.
  const [activeDay, setActiveDay] = useState(dayNumbers[0]);

  const activePlaces = days.get(activeDay) ?? [];
  const unassignedPlaces = localPlaces.filter((place) => place.dayNumber === null);

  async function handleMoveDay(place: SavedPlace, dayNumber: number) {
    const previous = localPlaces;
    setError(null);
    setLocalPlaces((current) =>
      current.map((p) => (p.id === place.id ? { ...p, dayNumber } : p))
    );
    setEmptyDayNumbers((current) => current.filter((d) => d !== dayNumber));
    try {
      const updated = await moveToDay(place.id, dayNumber);
      setLocalPlaces((current) => current.map((p) => (p.id === updated.id ? updated : p)));
    } catch {
      setLocalPlaces(previous);
      setError("장소를 옮기지 못했어요. 다시 시도해주세요.");
    }
  }

  async function handleReorder(place: SavedPlace, direction: "UP" | "DOWN") {
    const previous = localPlaces;
    setError(null);
    try {
      const updated = await reorderPlace(place.id, direction);
      setLocalPlaces((current) => current.map((p) => (p.id === updated.id ? updated : p)));
    } catch {
      setLocalPlaces(previous);
      setError("순서를 바꾸지 못했어요. 다시 시도해주세요.");
    }
  }

  function handleAddDay() {
    const maxDay = dayNumbers.length > 0 ? Math.max(...dayNumbers) : 0;
    const nextDay = maxDay + 1;
    setEmptyDayNumbers((current) => [...current, nextDay]);
    setActiveDay(nextDay);
  }

  function handleDeleteDay(day: number) {
    setEmptyDayNumbers((current) => current.filter((d) => d !== day));
    if (activeDay === day) {
      const remaining = dayNumbers.filter((d) => d !== day);
      setActiveDay(remaining[0]);
    }
  }

  return (
    <div className="flex flex-col gap-4 rounded-xl border border-border-subtle p-4">
      <div className="flex items-center justify-between">
        <div className="flex flex-wrap gap-2">
          {dayNumbers.map((day) => {
            const count = (days.get(day) ?? []).length;
            return (
              <div key={day} className="flex items-center gap-1">
                <button
                  type="button"
                  onClick={() => setActiveDay(day)}
                  className={`rounded-full px-4 py-1.5 text-sm font-medium transition-colors ${
                    day === activeDay
                      ? "bg-accent text-white"
                      : "bg-bg-muted text-ink-muted hover:text-ink"
                  }`}
                >
                  {day}일차
                </button>
                {editing && count === 0 && (
                  <button
                    type="button"
                    onClick={() => handleDeleteDay(day)}
                    aria-label={`${day}일차 삭제`}
                    className="text-xs text-ink-muted hover:text-accent"
                  >
                    ✕
                  </button>
                )}
              </div>
            );
          })}
          {editing && (
            <button
              type="button"
              onClick={handleAddDay}
              className="rounded-full border border-dashed border-border-subtle px-4 py-1.5 text-sm text-ink-muted hover:text-ink"
            >
              + 날짜 추가
            </button>
          )}
        </div>
        <button
          type="button"
          onClick={() => setEditing((current) => !current)}
          className="text-sm font-medium text-accent hover:underline"
        >
          {editing ? "편집 완료" : "편집"}
        </button>
      </div>

      {error && <p className="text-sm text-accent">{error}</p>}

      <PlaceMapSection
        key={activeDay}
        places={activePlaces}
        editable={editing}
        availableDayNumbers={dayNumbers}
        onMoveDay={handleMoveDay}
        onReorder={handleReorder}
      />

      {unassignedPlaces.length > 0 && (
        <div className="flex flex-col gap-3 border-t border-border-subtle pt-4">
          <p className="text-sm font-medium text-ink-muted">일자 미분류</p>
          <PlaceMapSection
            places={unassignedPlaces}
            editable={editing}
            availableDayNumbers={dayNumbers}
            onMoveDay={handleMoveDay}
          />
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 3: lint/build 확인**

Run: `npm run lint && npm run build`
Expected: 에러 없이 통과

- [ ] **Step 4: 수동 확인**

일정형 영상(파이프라인 판정이든 Task 7로 생성한 것이든) 상세 페이지에서:
1. "편집" 버튼을 눌러 편집 모드 진입 — 각 장소 카드 아래 위/아래 화살표 + 날짜 선택 드롭다운이 보이는지 확인
2. 위/아래 화살표로 순서를 바꾸면 즉시 반영되고, 새로고침해도 유지되는지 확인
3. 날짜 드롭다운으로 다른 날로 옮기면 해당 탭으로 장소가 이동하는지 확인
4. "+ 날짜 추가"로 빈 탭이 생기고, 장소를 하나 옮겨넣으면 정상 탭이 되는지 확인
5. 장소가 없는 빈 날짜 탭에서만 삭제(✕) 버튼이 활성화되는지 확인
6. 백엔드를 잠깐 내린 상태에서 순서 바꾸기를 시도해 에러 메시지가 뜨고 UI가 롤백되는지 확인

- [ ] **Step 5: 커밋**

```bash
git add src/components/PlaceMapSection.tsx src/components/ItineraryView.tsx
git commit -m "feat: 일정 편집 모드(날짜 이동/순서변경/날짜 추가삭제) 추가"
```
