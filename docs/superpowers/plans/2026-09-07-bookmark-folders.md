# 찜한 장소 폴더링 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 찜한 장소(`Bookmark`)를 네이버 지도처럼 사용자 정의 폴더로 분류해서
관리할 수 있게 한다 — 백엔드에 폴더 엔티티/API를 추가하고, 앱엔 지도+바텀시트
기반 "저장 장소" 화면을, 웹엔 `/bookmarks` 페이지에 폴더 그룹핑을 추가한다.

**Architecture:** `Bookmark`에 nullable `folder_id` FK 하나만 추가하는 얕은
데이터 모델(다대다 아님, 미분류는 `null`로 표현). 앱은 이미 설치된
`react-native-gesture-handler`/`react-native-reanimated` 위에 `@gorhom/bottom-sheet`
(순수 JS 패키지, 새 네이티브 링크 불필요)를 얹어 지도+바텀시트를 만든다.
웹은 지도 없이 폴더 탭 + 리스트로 같은 데이터를 보여준다(모바일 전용 패턴을
데스크톱에 억지로 옮기지 않는다).

**Tech Stack:** Spring Boot(백엔드, Hibernate `ddl-auto: update`라 마이그레이션
파일 불필요) / Expo+React Native(`@gorhom/bottom-sheet` 신규 추가) / Next.js(웹,
신규 의존성 없음)

**Spec:** `docs/superpowers/specs/2026-09-07-bookmark-folders-design.md`

## Global Constraints

- 폴더는 사용자당 여러 개, 찜 하나는 폴더 0개 또는 1개에만 속한다(다대다 금지)
- 폴더 삭제 시 소속 찜은 삭제하지 않고 `folder_id`를 `null`로 되돌린다
- 기존 `BookmarkService.addBookmark(User, Long)`(2-인자) 시그니처와 동작은
  그대로 유지한다 — 기존 테스트/호출부를 건드리지 않고, 새 3-인자
  오버로드로만 폴더 기능을 추가한다
- `GET /api/bookmarks`는 서버 쪽 폴더 필터를 만들지 않는다 — 응답에
  `folderId` 필드만 추가하고, 폴더별 보기는 클라이언트에서 필터링한다
- 찜 아이콘은 별(⭐ 찜함 / ☆ 안 찜함) — 이미 앱/웹 전체에 반영 완료, 이
  플랜에서 새로 만드는 화면도 이 아이콘을 그대로 쓴다
- 새 백엔드 코드는 `TripController`/`TripService` 등 기존 컨트롤러의
  `Authentication` → `currentUserService.resolve(authentication)` → 소유자
  필터(`.filter(x -> x.getUser().getId().equals(user.getId()))`) 패턴을
  그대로 따른다

---

## Task 1: `BookmarkFolder` 엔티티 + 리포지토리

**Files:**
- Create: `src/main/java/com/trova/backend/entity/BookmarkFolder.java`
- Create: `src/main/java/com/trova/backend/repository/BookmarkFolderRepository.java`
- Test: `src/test/java/com/trova/backend/entity/BookmarkFolderTest.java`

**Interfaces:**
- Produces: `BookmarkFolder(User user, String name, String color)` 생성자,
  `getId()/getUser()/getName()/getColor()/getCreatedAt()`.
  `BookmarkFolderRepository.findByUserOrderByCreatedAtDesc(User)`,
  `findByIdAndUser(Long, User)`.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.trova.backend.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BookmarkFolderTest {

    @Test
    void 생성하면_이름과_색상과_생성시각이_채워진다() {
        User user = new User("google", "folder-test", "테스트유저", null);

        BookmarkFolder folder = new BookmarkFolder(user, "카페 모음", "#4A90D9");

        assertThat(folder.getUser()).isEqualTo(user);
        assertThat(folder.getName()).isEqualTo("카페 모음");
        assertThat(folder.getColor()).isEqualTo("#4A90D9");
        assertThat(folder.getCreatedAt()).isBeforeOrEqualTo(LocalDateTime.now());
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.entity.BookmarkFolderTest"`
Expected: FAIL — `BookmarkFolder` 클래스가 없어서 컴파일 에러

- [ ] **Step 3: 엔티티 구현**

```java
package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 사용자가 찜한 장소를 정리하는 폴더(네이버 지도의 "목록"과 동일한 역할). */
@Entity
@Table(name = "bookmark_folders")
public class BookmarkFolder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String name;

    // 지도 핀 색상으로 쓰는 hex 문자열(예: "#4A90D9")
    @Column(nullable = false)
    private String color;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected BookmarkFolder() {
    }

    public BookmarkFolder(User user, String name, String color) {
        this.user = user;
        this.name = name;
        this.color = color;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getName() { return name; }
    public String getColor() { return color; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 4: 리포지토리 구현**

```java
package com.trova.backend.repository;

import com.trova.backend.entity.BookmarkFolder;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookmarkFolderRepository extends JpaRepository<BookmarkFolder, Long> {
    List<BookmarkFolder> findByUserOrderByCreatedAtDesc(User user);
    Optional<BookmarkFolder> findByIdAndUser(Long id, User user);
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.entity.BookmarkFolderTest"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/trova/backend/entity/BookmarkFolder.java \
        src/main/java/com/trova/backend/repository/BookmarkFolderRepository.java \
        src/test/java/com/trova/backend/entity/BookmarkFolderTest.java
git commit -m "feat: BookmarkFolder 엔티티/리포지토리 추가"
```

---

## Task 2: `Bookmark`에 폴더 연결 + 리포지토리 조회 메서드

**Files:**
- Modify: `src/main/java/com/trova/backend/entity/Bookmark.java`
- Modify: `src/main/java/com/trova/backend/repository/BookmarkRepository.java`
- Test: `src/test/java/com/trova/backend/entity/BookmarkTest.java` (신규)

**Interfaces:**
- Consumes: Task 1의 `BookmarkFolder`
- Produces: `Bookmark.getFolder()`, `Bookmark.applyFolder(BookmarkFolder)`.
  `BookmarkRepository.countByFolder(BookmarkFolder)`,
  `findByFolder(BookmarkFolder)`.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.trova.backend.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookmarkTest {

    @Test
    void 생성_직후엔_폴더가_없다() {
        User user = new User("google", "bm-test", "테스트유저", null);
        Place place = new Place("g1", "카페A", "cafe", 4.5, 100, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "주소");

        Bookmark bookmark = new Bookmark(user, place);

        assertThat(bookmark.getFolder()).isNull();
    }

    @Test
    void applyFolder로_폴더를_지정하거나_null로_되돌릴_수_있다() {
        User user = new User("google", "bm-test", "테스트유저", null);
        Place place = new Place("g1", "카페A", "cafe", 4.5, 100, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "주소");
        Bookmark bookmark = new Bookmark(user, place);
        BookmarkFolder folder = new BookmarkFolder(user, "카페 모음", "#4A90D9");

        bookmark.applyFolder(folder);
        assertThat(bookmark.getFolder()).isEqualTo(folder);

        bookmark.applyFolder(null);
        assertThat(bookmark.getFolder()).isNull();
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.entity.BookmarkTest"`
Expected: FAIL — `getFolder()`/`applyFolder(...)`가 없어서 컴파일 에러

- [ ] **Step 3: `Bookmark`에 필드/메서드 추가**

`src/main/java/com/trova/backend/entity/Bookmark.java`의 `place` 필드 아래에
추가:

```java
    // nullable — 미분류(폴더 없음)는 null로 표현한다. 별도의 "기본 폴더" row를
    // 만들지 않아서, 폴더를 지워도 찜이 고아가 되거나 사라지지 않는다.
    @ManyToOne
    @JoinColumn(name = "folder_id")
    private BookmarkFolder folder;
```

`getPlace()` 아래에 getter/mutator 추가:

```java
    public BookmarkFolder getFolder() { return folder; }

    public void applyFolder(BookmarkFolder folder) {
        this.folder = folder;
    }
```

- [ ] **Step 4: `BookmarkRepository`에 메서드 추가**

```java
package com.trova.backend.repository;

import com.trova.backend.entity.Bookmark;
import com.trova.backend.entity.BookmarkFolder;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {
    List<Bookmark> findByUserOrderByCreatedAtDesc(User user);
    Optional<Bookmark> findByUserAndPlace(User user, Place place);
    long countByFolder(BookmarkFolder folder);
    List<Bookmark> findByFolder(BookmarkFolder folder);
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.entity.BookmarkTest"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/trova/backend/entity/Bookmark.java \
        src/main/java/com/trova/backend/repository/BookmarkRepository.java \
        src/test/java/com/trova/backend/entity/BookmarkTest.java
git commit -m "feat: Bookmark에 폴더 연결 필드 추가"
```

---

## Task 3: `BookmarkService`에 폴더 CRUD/할당 로직 추가

**Files:**
- Modify: `src/main/java/com/trova/backend/service/BookmarkService.java`
- Modify: `src/test/java/com/trova/backend/service/BookmarkServiceTest.java`

**Interfaces:**
- Consumes: Task 1/2의 `BookmarkFolder`, `BookmarkFolderRepository`,
  `Bookmark.applyFolder`, `BookmarkRepository.countByFolder/findByFolder`
- Produces:
  - `BookmarkService.createFolder(User, String name, String color): BookmarkFolder`
  - `BookmarkService.listFolders(User): List<BookmarkService.FolderWithCount>`
    (`FolderWithCount(BookmarkFolder folder, long placeCount)` — 이 클래스
    안에 정의된 record)
  - `BookmarkService.deleteFolder(User, Long folderId): boolean`
  - `BookmarkService.moveToFolder(User, Long bookmarkId, Long folderId): Optional<Bookmark>`
    (`folderId == null`이면 미분류로 이동)
  - `BookmarkService.addBookmark(User, Long placeId, Long folderId): Optional<Bookmark>`
    (기존 2-인자 `addBookmark(User, Long)`은 그대로 두고, 이 3-인자
    오버로드를 새로 추가 — 2-인자 버전은 내부적으로 `addBookmark(user, placeId, null)`을 호출)

- [ ] **Step 1: 실패하는 테스트 작성**

`BookmarkServiceTest.java`에 아래 테스트들을 추가(기존 `@Mock` 필드 3개
아래에 `@Mock private BookmarkFolderRepository bookmarkFolderRepository;`
추가, `setUp()`의 `new BookmarkService(...)` 호출에 이 mock을 네 번째
인자로 추가):

```java
    @Mock private BookmarkFolderRepository bookmarkFolderRepository;

    // ... setUp()을 아래처럼 수정 ...
    private void setUp() {
        service = new BookmarkService(
                bookmarkRepository, placeRepository, userPreferenceRepository, bookmarkFolderRepository);
    }

    @Test
    void createFolder는_유저와_이름과_색상으로_폴더를_만든다() {
        setUp();
        when(bookmarkFolderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BookmarkFolder folder = service.createFolder(user, "카페 모음", "#4A90D9");

        assertThat(folder.getUser()).isEqualTo(user);
        assertThat(folder.getName()).isEqualTo("카페 모음");
        assertThat(folder.getColor()).isEqualTo("#4A90D9");
    }

    @Test
    void listFolders는_폴더별_찜_개수를_같이_돌려준다() {
        setUp();
        BookmarkFolder folder = new BookmarkFolder(user, "카페 모음", "#4A90D9");
        when(bookmarkFolderRepository.findByUserOrderByCreatedAtDesc(user)).thenReturn(java.util.List.of(folder));
        when(bookmarkRepository.countByFolder(folder)).thenReturn(3L);

        var result = service.listFolders(user);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).folder()).isEqualTo(folder);
        assertThat(result.get(0).placeCount()).isEqualTo(3L);
    }

    @Test
    void deleteFolder는_소속_찜을_미분류로_되돌리고_폴더를_지운다() {
        setUp();
        BookmarkFolder folder = new BookmarkFolder(user, "카페 모음", "#4A90D9");
        Place place = place("CALM");
        Bookmark member = new Bookmark(user, place);
        member.applyFolder(folder);
        when(bookmarkFolderRepository.findByIdAndUser(10L, user)).thenReturn(Optional.of(folder));
        when(bookmarkRepository.findByFolder(folder)).thenReturn(java.util.List.of(member));

        boolean deleted = service.deleteFolder(user, 10L);

        assertThat(deleted).isTrue();
        assertThat(member.getFolder()).isNull();
        verify(bookmarkRepository).save(member);
        verify(bookmarkFolderRepository).delete(folder);
    }

    @Test
    void deleteFolder는_소유자가_아니면_false를_돌려준다() {
        setUp();
        when(bookmarkFolderRepository.findByIdAndUser(10L, user)).thenReturn(Optional.empty());

        boolean deleted = service.deleteFolder(user, 10L);

        assertThat(deleted).isFalse();
    }

    @Test
    void moveToFolder는_찜을_다른_폴더로_옮긴다() {
        setUp();
        Place place = place("CALM");
        Bookmark bookmark = new Bookmark(user, place);
        BookmarkFolder folder = new BookmarkFolder(user, "카페 모음", "#4A90D9");
        when(bookmarkRepository.findById(5L)).thenReturn(Optional.of(bookmark));
        when(bookmarkFolderRepository.findByIdAndUser(10L, user)).thenReturn(Optional.of(folder));
        when(bookmarkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Bookmark result = service.moveToFolder(user, 5L, 10L).orElseThrow();

        assertThat(result.getFolder()).isEqualTo(folder);
    }

    @Test
    void moveToFolder에_null을_주면_미분류로_옮긴다() {
        setUp();
        Place place = place("CALM");
        Bookmark bookmark = new Bookmark(user, place);
        BookmarkFolder folder = new BookmarkFolder(user, "카페 모음", "#4A90D9");
        bookmark.applyFolder(folder);
        when(bookmarkRepository.findById(5L)).thenReturn(Optional.of(bookmark));
        when(bookmarkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Bookmark result = service.moveToFolder(user, 5L, null).orElseThrow();

        assertThat(result.getFolder()).isNull();
    }

    @Test
    void moveToFolder는_존재하지_않는_폴더면_빈_Optional을_돌려준다() {
        setUp();
        Place place = place("CALM");
        Bookmark bookmark = new Bookmark(user, place);
        when(bookmarkRepository.findById(5L)).thenReturn(Optional.of(bookmark));
        when(bookmarkFolderRepository.findByIdAndUser(999L, user)).thenReturn(Optional.empty());

        Optional<Bookmark> result = service.moveToFolder(user, 5L, 999L);

        assertThat(result).isEmpty();
    }

    @Test
    void addBookmark에_folderId를_주면_그_폴더에_바로_배정된다() {
        setUp();
        Place place = place("TRENDY");
        BookmarkFolder folder = new BookmarkFolder(user, "카페 모음", "#4A90D9");
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(bookmarkRepository.findByUserAndPlace(user, place)).thenReturn(Optional.empty());
        when(bookmarkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userPreferenceRepository.findByUserAndMood(user, "TRENDY")).thenReturn(Optional.empty());
        when(bookmarkFolderRepository.findByIdAndUser(10L, user)).thenReturn(Optional.of(folder));

        Bookmark result = service.addBookmark(user, 1L, 10L).orElseThrow();

        assertThat(result.getFolder()).isEqualTo(folder);
    }
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.trova.backend.service.BookmarkServiceTest"`
Expected: FAIL — `BookmarkService` 생성자 인자 개수 불일치 및 새 메서드
없음으로 컴파일 에러

- [ ] **Step 3: `BookmarkService` 구현**

`src/main/java/com/trova/backend/service/BookmarkService.java` 전체를
아래로 교체:

```java
package com.trova.backend.service;

import com.trova.backend.entity.Bookmark;
import com.trova.backend.entity.BookmarkFolder;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.User;
import com.trova.backend.entity.UserPreference;
import com.trova.backend.repository.BookmarkFolderRepository;
import com.trova.backend.repository.BookmarkRepository;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.UserPreferenceRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 추천엔진 결과를 즐겨찾기하면 그 장소의 mood 선호 점수를 올린다(양성 신호만
 * — "추천했는데 안 고름" 음성 신호는 프론트에 추천 화면이 붙기 전까진 없음).
 * 찜은 폴더(BookmarkFolder)로 정리할 수 있다 — 폴더 없음(null)은 미분류.
 */
@Service
public class BookmarkService {

    private static final double BOOKMARK_SCORE_DELTA = 1.0;

    private final BookmarkRepository bookmarkRepository;
    private final PlaceRepository placeRepository;
    private final UserPreferenceRepository userPreferenceRepository;
    private final BookmarkFolderRepository bookmarkFolderRepository;

    public BookmarkService(
            BookmarkRepository bookmarkRepository,
            PlaceRepository placeRepository,
            UserPreferenceRepository userPreferenceRepository,
            BookmarkFolderRepository bookmarkFolderRepository
    ) {
        this.bookmarkRepository = bookmarkRepository;
        this.placeRepository = placeRepository;
        this.userPreferenceRepository = userPreferenceRepository;
        this.bookmarkFolderRepository = bookmarkFolderRepository;
    }

    public record FolderWithCount(BookmarkFolder folder, long placeCount) {
    }

    public Optional<Bookmark> addBookmark(User user, Long placeId) {
        return addBookmark(user, placeId, null);
    }

    public Optional<Bookmark> addBookmark(User user, Long placeId, Long folderId) {
        return placeRepository.findById(placeId).map(place -> {
            Optional<Bookmark> existing = bookmarkRepository.findByUserAndPlace(user, place);
            if (existing.isPresent()) {
                return existing.get();
            }

            if (place.getMood() != null) {
                bumpPreference(user, place.getMood());
            }
            Bookmark bookmark = bookmarkRepository.save(new Bookmark(user, place));
            if (folderId != null) {
                bookmarkFolderRepository.findByIdAndUser(folderId, user).ifPresent(bookmark::applyFolder);
                bookmark = bookmarkRepository.save(bookmark);
            }
            return bookmark;
        });
    }

    private void bumpPreference(User user, String mood) {
        UserPreference preference = userPreferenceRepository.findByUserAndMood(user, mood)
                .orElseGet(() -> new UserPreference(user, mood, 0.0));
        preference.addScore(BOOKMARK_SCORE_DELTA);
        userPreferenceRepository.save(preference);
    }

    public boolean removeBookmark(User user, Long bookmarkId) {
        return bookmarkRepository.findById(bookmarkId)
                .filter(b -> b.getUser().getId().equals(user.getId()))
                .map(b -> {
                    bookmarkRepository.delete(b);
                    return true;
                })
                .orElse(false);
    }

    public BookmarkFolder createFolder(User user, String name, String color) {
        return bookmarkFolderRepository.save(new BookmarkFolder(user, name, color));
    }

    public List<FolderWithCount> listFolders(User user) {
        return bookmarkFolderRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(folder -> new FolderWithCount(folder, bookmarkRepository.countByFolder(folder)))
                .toList();
    }

    /** 소속 찜은 지우지 않고 미분류(folder=null)로 되돌린 뒤 폴더를 삭제한다. */
    public boolean deleteFolder(User user, Long folderId) {
        return bookmarkFolderRepository.findByIdAndUser(folderId, user)
                .map(folder -> {
                    for (Bookmark member : bookmarkRepository.findByFolder(folder)) {
                        member.applyFolder(null);
                        bookmarkRepository.save(member);
                    }
                    bookmarkFolderRepository.delete(folder);
                    return true;
                })
                .orElse(false);
    }

    /** folderId가 null이면 미분류로 옮긴다. folderId를 줬는데 그 폴더가 없거나
     *  내 폴더가 아니면 빈 Optional을 돌려준다(찜을 엉뚱한 상태로 두지 않기 위해). */
    public Optional<Bookmark> moveToFolder(User user, Long bookmarkId, Long folderId) {
        Optional<Bookmark> bookmarkOpt = bookmarkRepository.findById(bookmarkId)
                .filter(b -> b.getUser().getId().equals(user.getId()));
        if (bookmarkOpt.isEmpty()) {
            return Optional.empty();
        }
        Bookmark bookmark = bookmarkOpt.get();

        if (folderId == null) {
            bookmark.applyFolder(null);
            return Optional.of(bookmarkRepository.save(bookmark));
        }
        return bookmarkFolderRepository.findByIdAndUser(folderId, user)
                .map(folder -> {
                    bookmark.applyFolder(folder);
                    return bookmarkRepository.save(bookmark);
                });
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.trova.backend.service.BookmarkServiceTest"`
Expected: PASS (기존 테스트 5개 + 신규 8개, 총 13개)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/trova/backend/service/BookmarkService.java \
        src/test/java/com/trova/backend/service/BookmarkServiceTest.java
git commit -m "feat: BookmarkService에 폴더 생성/조회/삭제/이동 추가"
```

---

## Task 4: `BookmarkController`에 폴더 엔드포인트 추가

**Files:**
- Modify: `src/main/java/com/trova/backend/controller/BookmarkController.java`

**Interfaces:**
- Consumes: Task 3의 `BookmarkService` 메서드들
- Produces: `POST/GET /api/bookmarks/folders`, `DELETE /api/bookmarks/folders/{id}`,
  `PATCH /api/bookmarks/{id}`. 기존 `GET/POST/DELETE /api/bookmarks`는
  경로 그대로, `BookmarkResponse`에 `folderId` 필드만 추가.

이 컨트롤러는 단순 위임 로직이라(서비스에서 이미 테스트함) 별도 단위
테스트 없이 수동 curl 검증으로 충분하다 — 기존 `AuthControllerTest`류
통합 테스트 패턴이 이 레포에 없다.

- [ ] **Step 1: `BookmarkController` 전체 교체**

```java
package com.trova.backend.controller;

import com.trova.backend.entity.Bookmark;
import com.trova.backend.entity.User;
import com.trova.backend.repository.BookmarkRepository;
import com.trova.backend.service.BookmarkService;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bookmarks")
public class BookmarkController {

    private final CurrentUserService currentUserService;
    private final BookmarkService bookmarkService;
    private final BookmarkRepository bookmarkRepository;

    public BookmarkController(
            CurrentUserService currentUserService, BookmarkService bookmarkService, BookmarkRepository bookmarkRepository
    ) {
        this.currentUserService = currentUserService;
        this.bookmarkService = bookmarkService;
        this.bookmarkRepository = bookmarkRepository;
    }

    public record CreateBookmarkRequest(Long placeId, Long folderId) {
    }

    public record MoveBookmarkRequest(Long folderId) {
    }

    public record CreateFolderRequest(String name, String color) {
    }

    public record BookmarkResponse(
            Long id, Long placeId, String placeName, String googlePlaceId, String mood, String space,
            Double latitude, Double longitude, String createdAt, Long folderId
    ) {
        static BookmarkResponse from(Bookmark b) {
            return new BookmarkResponse(
                    b.getId(), b.getPlace().getId(), b.getPlace().getName(), b.getPlace().getGooglePlaceId(),
                    b.getPlace().getMood(), b.getPlace().getSpace(),
                    b.getPlace().getLatitude(), b.getPlace().getLongitude(), b.getCreatedAt().toString(),
                    b.getFolder() != null ? b.getFolder().getId() : null);
        }
    }

    public record BookmarkFolderResponse(Long id, String name, String color, long placeCount) {
        static BookmarkFolderResponse from(BookmarkService.FolderWithCount fw) {
            return new BookmarkFolderResponse(
                    fw.folder().getId(), fw.folder().getName(), fw.folder().getColor(), fw.placeCount());
        }
    }

    @GetMapping
    public List<BookmarkResponse> list(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        return bookmarkRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(BookmarkResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<BookmarkResponse> create(
            Authentication authentication, @RequestBody CreateBookmarkRequest request
    ) {
        if (request == null || request.placeId() == null) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        return bookmarkService.addBookmark(user, request.placeId(), request.folderId())
                .map(b -> ResponseEntity.ok(BookmarkResponse.from(b)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        boolean removed = bookmarkService.removeBookmark(user, id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PatchMapping("/{id}")
    public ResponseEntity<BookmarkResponse> move(
            Authentication authentication, @PathVariable Long id, @RequestBody MoveBookmarkRequest request
    ) {
        User user = currentUserService.resolve(authentication);
        return bookmarkService.moveToFolder(user, id, request == null ? null : request.folderId())
                .map(b -> ResponseEntity.ok(BookmarkResponse.from(b)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/folders")
    public List<BookmarkFolderResponse> listFolders(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        return bookmarkService.listFolders(user).stream().map(BookmarkFolderResponse::from).toList();
    }

    @PostMapping("/folders")
    public ResponseEntity<BookmarkFolderResponse> createFolder(
            Authentication authentication, @RequestBody CreateFolderRequest request
    ) {
        if (request == null || request.name() == null || request.name().isBlank()
                || request.color() == null || request.color().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        var folder = bookmarkService.createFolder(user, request.name(), request.color());
        return ResponseEntity.ok(BookmarkFolderResponse.from(new BookmarkService.FolderWithCount(folder, 0)));
    }

    @DeleteMapping("/folders/{id}")
    public ResponseEntity<Void> deleteFolder(Authentication authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        boolean removed = bookmarkService.deleteFolder(user, id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
```

- [ ] **Step 2: 빌드 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 백엔드 재시작**

워크트리 루트에서: `set -a && source .env && set +a && ./gradlew bootRun`
(이미 이 세션에서 여러 번 쓴 방식과 동일). 정상 기동 로그
(`Started TrovaBackendApplication`)를 확인한다.

- [ ] **Step 4: 인증 없이 401 확인(라우트가 실제로 등록됐는지 최소 확인)**

```bash
curl -s -o /dev/null -w "http_code=%{http_code}\n" http://localhost:8080/api/bookmarks/folders
```

Expected: `http_code=401` (인증 안 된 요청이 시큐리티에서 거부됨 — 404가
아니라 401이 나온다는 건 라우트 자체는 등록됐다는 뜻). 실제 데이터가 오가는
전체 플로우 검증은 Task 9/10에서 앱을 통해 하는 게 JWT를 직접 다루는 것보다
빠르다 — 로그인된 앱에서 ⭐를 눌러 폴더를 만들고 저장해보면 된다.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/trova/backend/controller/BookmarkController.java
git commit -m "feat: 찜 폴더 CRUD/이동 API 추가"
```

---

## Task 5: 앱 — 공유 색상 팔레트 모듈 추출

기존 `itinerary.ts`의 `DAY_COLORS` 배열을 폴더 색상 프리셋으로도 재사용한다
(임의의 새 색을 고르지 않고, 이미 검증된 "구분되는 색" 세트를 공유).

**Files:**
- Create: `src/lib/colorPresets.ts`
- Modify: `src/lib/itinerary.ts`

**Interfaces:**
- Produces: `DISTINCT_COLORS: readonly string[]` (6개 hex 색상)

- [ ] **Step 1: `colorPresets.ts` 작성**

```ts
// 일정 날짜 구분 색, 찜 폴더 색상 등 "여러 개를 구분해서 보여줘야 하는" 곳에서
// 공용으로 쓰는 팔레트. 새 색을 추가하고 싶으면 여기 하나만 바꾸면 된다.
export const DISTINCT_COLORS = [
  "#FF6B4A",
  "#4A90D9",
  "#4AC98F",
  "#D9A94A",
  "#9B6BD9",
  "#D94A8C",
] as const;
```

- [ ] **Step 2: `itinerary.ts`가 이 배열을 재사용하도록 수정**

`src/lib/itinerary.ts`에서:

```ts
// 웹(trova-frontend/src/lib/itinerary.ts)의 DAY_COLORS를 그대로 이식한 값.
// #FF6B4A는 "옛 accent 값"이 아니라 웹이 지금도 지도 핀 기본색으로 쓰는
// 현역 색이다(KakaoMap.tsx, PlaceMapSection.tsx) — colors.accent(버튼 강조색)로
// 바꾸지 말 것, 웹과 다른 주황이 된다.
const DAY_COLORS = ["#FF6B4A", "#4A90D9", "#4AC98F", "#D9A94A", "#9B6BD9", "#D94A8C"];
```

를 아래로 교체:

```ts
import { DISTINCT_COLORS } from "@/lib/colorPresets";

// 웹(trova-frontend/src/lib/itinerary.ts)의 DAY_COLORS와 값이 같아야 한다.
// #FF6B4A는 "옛 accent 값"이 아니라 웹이 지금도 지도 핀 기본색으로 쓰는
// 현역 색이다(KakaoMap.tsx, PlaceMapSection.tsx) — colors.accent(버튼 강조색)로
// 바꾸지 말 것, 웹과 다른 주황이 된다.
const DAY_COLORS = DISTINCT_COLORS;
```

(파일 맨 위 import 구역에 `import { DISTINCT_COLORS } from "@/lib/colorPresets";`
추가)

- [ ] **Step 3: 타입체크**

Run: `cd /Users/gimtaehyeong/Desktop/trova-app && npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add src/lib/colorPresets.ts src/lib/itinerary.ts
git commit -m "refactor: 구분 색상 팔레트를 공용 모듈로 추출"
```

---

## Task 6: 앱 — `api/bookmarks.ts`에 폴더 API 추가

**Files:**
- Modify: `src/lib/api/bookmarks.ts`

**Interfaces:**
- Produces: `Bookmark.folderId: number | null` (타입 필드 추가),
  `type BookmarkFolder = {id, name, color, placeCount}`,
  `listFolders(): Promise<BookmarkFolder[]>`,
  `createFolder(name, color): Promise<BookmarkFolder>`,
  `deleteFolder(id): Promise<void>`,
  `moveBookmarkToFolder(bookmarkId, folderId: number | null): Promise<Bookmark>`,
  `addBookmark(placeId, folderId?): Promise<Bookmark>` (기존 함수에 선택
  인자 추가 — 기존 호출부는 수정 불필요)

- [ ] **Step 1: 파일 전체 교체**

```ts
import { apiFetch } from "@/lib/api/client";

export type Bookmark = {
  id: number;
  placeId: number;
  placeName: string;
  googlePlaceId: string;
  mood: string | null;
  space: string | null;
  latitude: number | null;
  longitude: number | null;
  createdAt: string;
  folderId: number | null;
};

export type BookmarkFolder = {
  id: number;
  name: string;
  color: string;
  placeCount: number;
};

export async function listBookmarks(): Promise<Bookmark[]> {
  const res = await apiFetch(`/api/bookmarks`);
  if (!res.ok) {
    throw new Error(`GET /api/bookmarks failed: ${res.status}`);
  }
  return res.json();
}

export async function addBookmark(placeId: number, folderId?: number | null): Promise<Bookmark> {
  const res = await apiFetch(`/api/bookmarks`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ placeId, folderId: folderId ?? null }),
  });
  if (!res.ok) {
    throw new Error(`POST /api/bookmarks failed: ${res.status}`);
  }
  return res.json();
}

export async function removeBookmark(id: number): Promise<void> {
  const res = await apiFetch(`/api/bookmarks/${id}`, { method: "DELETE" });
  if (!res.ok) {
    throw new Error(`DELETE /api/bookmarks/${id} failed: ${res.status}`);
  }
}

export async function moveBookmarkToFolder(bookmarkId: number, folderId: number | null): Promise<Bookmark> {
  const res = await apiFetch(`/api/bookmarks/${bookmarkId}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ folderId }),
  });
  if (!res.ok) {
    throw new Error(`PATCH /api/bookmarks/${bookmarkId} failed: ${res.status}`);
  }
  return res.json();
}

export async function listFolders(): Promise<BookmarkFolder[]> {
  const res = await apiFetch(`/api/bookmarks/folders`);
  if (!res.ok) {
    throw new Error(`GET /api/bookmarks/folders failed: ${res.status}`);
  }
  return res.json();
}

export async function createFolder(name: string, color: string): Promise<BookmarkFolder> {
  const res = await apiFetch(`/api/bookmarks/folders`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name, color }),
  });
  if (!res.ok) {
    throw new Error(`POST /api/bookmarks/folders failed: ${res.status}`);
  }
  return res.json();
}

export async function deleteFolder(id: number): Promise<void> {
  const res = await apiFetch(`/api/bookmarks/folders/${id}`, { method: "DELETE" });
  if (!res.ok) {
    throw new Error(`DELETE /api/bookmarks/folders/${id} failed: ${res.status}`);
  }
}
```

- [ ] **Step 2: 타입체크**

Run: `cd /Users/gimtaehyeong/Desktop/trova-app && npx tsc --noEmit`
Expected: 에러 없음(기존 `addBookmark(place.id)` 호출부들은 두 번째 인자가
선택값이라 그대로 컴파일된다)

- [ ] **Step 3: 커밋**

```bash
git add src/lib/api/bookmarks.ts
git commit -m "feat: 찜 폴더 API 클라이언트 함수 추가"
```

---

## Task 7: 앱 — `InlineMap`/`kakaoMapHtml`에 핀별 색상 + 동선 끄기 옵션 추가

지금은 모든 핀이 `#FF6B4A` 고정색이고 항상 동선(폴리라인)을 그린다.
"저장 장소" 지도는 폴더별로 색이 달라야 하고, 방문 순서가 아니라 흩어진
장소들이라 동선을 그리면 안 된다.

**Files:**
- Modify: `src/lib/kakaoMapHtml.ts`
- Modify: `src/components/InlineMap.tsx`

**Interfaces:**
- Produces: `InlineMap` props에 `showPath?: boolean`(기본 `true`, 기존
  화면들 동작 그대로 유지), `fill?: boolean`(기본 `false` — 풀스크린용),
  `Pin` 타입에 `color?: string` 추가

- [ ] **Step 1: `kakaoMapHtml.ts`의 `renderPins`/`handleMessage` 수정**

`overlays.forEach(...); overlays = [];` 다음, `var path = pins.map(...)`
윗줄에 있는 시그니처를 바꾼다. `renderPins(pins, selectedId)`를
`renderPins(pins, selectedId, showPath)`로, 핀 배경색을 고정값 대신
`pin.color`로, 폴리라인 조건에 `showPath &&`를 추가:

```js
    function renderPins(pins, selectedId, showPath) {
      if (!pins || pins.length === 0) return;
      kakao.maps.load(function () {
        var container = document.getElementById('map');
        var first = pins[0];
        var center = new kakao.maps.LatLng(first.latitude, first.longitude);
        if (mapInstance === null) {
          mapInstance = new kakao.maps.Map(container, { center: center, level: 4 });
        } else {
          mapInstance.setCenter(center);
        }

        overlays.forEach(function (overlay) { overlay.setMap(null); });
        overlays = [];
        if (polyline) {
          polyline.setMap(null);
          polyline = null;
        }

        var path = pins.map(function (pin) {
          return new kakao.maps.LatLng(pin.latitude, pin.longitude);
        });

        positions = {};
        pins.forEach(function (pin, index) {
          var position = path[index];
          var pinColor = pin.color || '#FF6B4A';
          positions[pin.id] = position;
          var el = document.createElement('div');
          el.textContent = String(index + 1);
          el.style.cssText = 'width:26px;height:26px;border-radius:9999px;background:' + pinColor + ';' +
            'color:#fff;display:flex;align-items:center;justify-content:center;' +
            'font-size:12px;font-weight:700;border:2px solid #fff;box-shadow:0 1px 3px rgba(0,0,0,0.35);';
          var overlay = new kakao.maps.CustomOverlay({ position: position, content: el, zIndex: 2 });
          overlay.setMap(mapInstance);
          overlays.push(overlay);
        });

        // 웹(KakaoMap.tsx)의 동선 선 스타일을 그대로 이식 — strokeWeight/strokeColor/strokeOpacity 동일.
        if (showPath && path.length > 1) {
          polyline = new kakao.maps.Polyline({
            path: path,
            strokeWeight: 3,
            strokeColor: '#FF6B4A',
            strokeOpacity: 0.8,
          });
          polyline.setMap(mapInstance);
        }

        if (highlightOverlay === null) {
          highlightOverlay = new kakao.maps.CustomOverlay({
            position: center,
            content: buildHighlightHtml(),
            zIndex: 1,
          });
        }
        applySelection(selectedId);
      });
    }

    function handleMessage(event) {
      var payload;
      try {
        payload = JSON.parse(event.data);
      } catch (e) {
        // 무시 — 핀 데이터가 아닌 다른 메시지일 수 있음
        return;
      }
      try {
        renderPins(payload.pins, payload.selectedId, payload.showPath !== false);
      } catch (e) {
        window.ReactNativeWebView && window.ReactNativeWebView.postMessage(
          JSON.stringify({ type: 'render-error', message: String(e && e.message) })
        );
      }
    }
```

(`payload.showPath !== false`로 기본값을 `true`로 둔다 — 기존 화면들이
`showPath`를 안 보내도 지금처럼 동선이 그려진다.)

- [ ] **Step 2: `InlineMap.tsx` 전체 교체**

```tsx
import { useEffect, useRef, useState } from "react";
import { View } from "react-native";
import { WebView, type WebViewMessageEvent } from "react-native-webview";
import { AppText } from "@/components/AppText";
import { buildKakaoMapHtml } from "@/lib/kakaoMapHtml";
import { colors } from "@/lib/theme";

const KAKAO_MAP_JS_KEY = process.env.EXPO_PUBLIC_KAKAO_MAP_JS_KEY ?? "";

type Pin = { id: string; latitude: number; longitude: number; color?: string };

export function InlineMap({
  pins,
  height = 200,
  selectedId = null,
  showPath = true,
  fill = false,
}: {
  pins: Pin[];
  height?: number;
  // 있으면 해당 핀으로 지도를 이동시키고 하이라이트 링을 보여준다(웹 KakaoMap.tsx와 동일).
  selectedId?: string | null;
  // 방문 순서 동선을 표시할 필요가 없는 화면(예: 찜 폴더 지도)에서 false로 끈다.
  showPath?: boolean;
  // true면 고정 높이 대신 부모를 꽉 채운다(풀스크린 지도 화면용).
  fill?: boolean;
}) {
  const webviewRef = useRef<WebView>(null);
  const [isMapLoaded, setIsMapLoaded] = useState(false);
  const [mapError, setMapError] = useState<string | null>(null);

  // 호출부는 매 렌더마다 pins 배열을 새로 만든다(메모이제이션 없음). 배열 참조를
  // 의존성으로 쓰면 부모가 리렌더될 때마다(예: 검색어 한 글자 입력) WebView에
  // postMessage가 다시 나가 카카오 지도가 통째로 다시 만들어진다.
  // 그래서 참조가 아니라 "핀 내용"을 직렬화한 문자열을 의존성으로 쓴다 —
  // 내용이 실제로 바뀔 때만 새 메시지가 나간다.
  const pinsKey = JSON.stringify(
    pins.map((pin) => ({ id: pin.id, latitude: pin.latitude, longitude: pin.longitude, color: pin.color ?? null }))
  );
  const payload = JSON.stringify({ pins: pins.length > 0 ? JSON.parse(pinsKey) : [], selectedId, showPath });

  useEffect(() => {
    if (!isMapLoaded || mapError || pinsKey === "[]") return;
    webviewRef.current?.postMessage(payload);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isMapLoaded, mapError, pinsKey, selectedId, showPath]);

  function handleMessage(event: WebViewMessageEvent) {
    try {
      const data = JSON.parse(event.nativeEvent.data);
      if (data?.type === "sdk-load-error" || data?.type === "render-error") {
        setMapError("지도를 불러오지 못했어요.");
      }
    } catch {
      // 무시 — 지도 쪽에서 보낸 다른 형식의 메시지일 수 있음
    }
  }

  if (pins.length === 0) return null;

  const containerStyle = fill ? { flex: 1 as const } : { height, borderRadius: 12, overflow: "hidden" as const };

  if (!KAKAO_MAP_JS_KEY) {
    return (
      <View style={[containerStyle, { justifyContent: "center", alignItems: "center", backgroundColor: colors.bgMuted }]}>
        <AppText style={{ color: colors.inkMuted }}>지도 키가 설정되지 않았어요.</AppText>
      </View>
    );
  }

  if (mapError) {
    return (
      <View style={[containerStyle, { justifyContent: "center", alignItems: "center", backgroundColor: colors.bgMuted }]}>
        <AppText style={{ color: colors.inkMuted }}>{mapError}</AppText>
      </View>
    );
  }

  return (
    <WebView
      ref={webviewRef}
      source={{ html: buildKakaoMapHtml(KAKAO_MAP_JS_KEY), baseUrl: "https://localhost" }}
      style={containerStyle}
      onLoadEnd={() => setIsMapLoaded(true)}
      onMessage={handleMessage}
      onError={() => {
        setIsMapLoaded(false);
        setMapError("지도를 불러오지 못했어요.");
      }}
      onHttpError={() => {
        setIsMapLoaded(false);
        setMapError("지도를 불러오지 못했어요.");
      }}
    />
  );
}
```

- [ ] **Step 3: 타입체크**

Run: `cd /Users/gimtaehyeong/Desktop/trova-app && npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 4: 수동 확인**

Metro가 떠 있으면 JS만 바뀐 거라 재빌드 불필요. 시뮬레이터에서 여행
상세 화면(기존 `InlineMap` 사용처)을 열어서 핀/동선이 그대로 보이는지
확인한다(회귀 없어야 함 — `showPath` 기본값이 `true`라 동작이 같아야 함).

- [ ] **Step 5: 커밋**

```bash
git add src/lib/kakaoMapHtml.ts src/components/InlineMap.tsx
git commit -m "feat: 지도 핀 색상/동선 표시 여부를 커스터마이즈 가능하게 함"
```

---

## Task 8: 앱 — `FolderPickerModal` 컴포넌트

⭐를 누를 때(또는 폴더 없이 이미 찜한 걸 나중에 옮길 때) 폴더를 고르거나
새로 만드는 모달. `PlaceReviewModal`과 같은 바텀시트 스타일을 따른다.

**Files:**
- Create: `src/components/FolderPickerModal.tsx`

**Interfaces:**
- Consumes: `listFolders`, `createFolder` (Task 6), `DISTINCT_COLORS`
  (Task 5)
- Produces: `FolderPickerModal({visible, onClose, onPick}: {visible: boolean;
  onClose: () => void; onPick: (folderId: number | null) => void})` — 폴더를
  고르거나 새로 만들면 `onPick(folderId)`를 호출하고 호출부가 실제 저장/이동
  API를 부른다(이 컴포넌트는 "무엇을 할지"만 알려주고 실제 부수효과는 호출부
  책임 — 저장용/이동용 양쪽에서 재사용하기 위해).

- [ ] **Step 1: 컴포넌트 작성**

```tsx
import { useState } from "react";
import { Modal, Pressable, ScrollView, TextInput, View } from "react-native";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AppText } from "@/components/AppText";
import { DISTINCT_COLORS } from "@/lib/colorPresets";
import { createFolder, listFolders } from "@/lib/api/bookmarks";
import { colors } from "@/lib/theme";

export function FolderPickerModal({
  visible,
  onClose,
  onPick,
}: {
  visible: boolean;
  onClose: () => void;
  // 폴더를 고르거나(기존 폴더 id) 미분류로 저장(null)하면 호출된다.
  // 실제 addBookmark/moveBookmarkToFolder 호출은 이 컴포넌트를 쓰는 화면의 책임.
  onPick: (folderId: number | null) => void;
}) {
  const queryClient = useQueryClient();
  const foldersQuery = useQuery({ queryKey: ["bookmarkFolders"], queryFn: listFolders, enabled: visible });
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState("");
  const [newColor, setNewColor] = useState<string>(DISTINCT_COLORS[0]);
  const [busy, setBusy] = useState(false);

  function reset() {
    setCreating(false);
    setNewName("");
    setNewColor(DISTINCT_COLORS[0]);
  }

  async function handleCreateAndPick() {
    if (!newName.trim() || busy) return;
    setBusy(true);
    try {
      const folder = await createFolder(newName.trim(), newColor);
      await queryClient.invalidateQueries({ queryKey: ["bookmarkFolders"] });
      reset();
      onPick(folder.id);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      visible={visible}
      transparent
      animationType="slide"
      onRequestClose={onClose}
      onDismiss={reset}
    >
      <Pressable style={{ flex: 1, backgroundColor: "rgba(0,0,0,0.3)", justifyContent: "flex-end" }} onPress={onClose}>
        <Pressable
          style={{ maxHeight: "70%", backgroundColor: colors.bg, borderTopLeftRadius: 16, borderTopRightRadius: 16 }}
          onPress={(e) => e.stopPropagation()}
        >
          <ScrollView contentContainerStyle={{ padding: 20, gap: 12 }}>
            <AppText weight="medium" style={{ fontSize: 16 }}>
              어느 폴더에 저장할까요?
            </AppText>

            <Pressable
              onPress={() => onPick(null)}
              style={{ padding: 12, borderRadius: 10, borderWidth: 1, borderColor: colors.border }}
            >
              <AppText>미분류로 저장</AppText>
            </Pressable>

            {(foldersQuery.data ?? []).map((folder) => (
              <Pressable
                key={folder.id}
                onPress={() => onPick(folder.id)}
                style={{
                  flexDirection: "row",
                  alignItems: "center",
                  gap: 10,
                  padding: 12,
                  borderRadius: 10,
                  borderWidth: 1,
                  borderColor: colors.border,
                }}
              >
                <View style={{ width: 12, height: 12, borderRadius: 6, backgroundColor: folder.color }} />
                <AppText style={{ flex: 1 }}>{folder.name}</AppText>
                <AppText style={{ fontSize: 12, color: colors.inkMuted }}>{folder.placeCount}개</AppText>
              </Pressable>
            ))}

            {creating ? (
              <View style={{ gap: 10, padding: 12, borderRadius: 10, borderWidth: 1, borderColor: colors.border }}>
                <TextInput
                  autoFocus
                  value={newName}
                  onChangeText={setNewName}
                  placeholder="새 폴더 이름"
                  style={{
                    borderWidth: 1,
                    borderColor: colors.border,
                    borderRadius: 8,
                    paddingHorizontal: 10,
                    paddingVertical: 8,
                    fontSize: 14,
                  }}
                />
                <View style={{ flexDirection: "row", gap: 8 }}>
                  {DISTINCT_COLORS.map((color) => (
                    <Pressable
                      key={color}
                      onPress={() => setNewColor(color)}
                      style={{
                        width: 28,
                        height: 28,
                        borderRadius: 14,
                        backgroundColor: color,
                        borderWidth: newColor === color ? 3 : 0,
                        borderColor: colors.ink,
                      }}
                    />
                  ))}
                </View>
                <Pressable
                  onPress={handleCreateAndPick}
                  disabled={!newName.trim() || busy}
                  style={{
                    height: 40,
                    borderRadius: 8,
                    backgroundColor: colors.accent,
                    justifyContent: "center",
                    alignItems: "center",
                    opacity: !newName.trim() || busy ? 0.5 : 1,
                  }}
                >
                  <AppText weight="medium" style={{ color: "#fff" }}>
                    만들고 저장
                  </AppText>
                </Pressable>
              </View>
            ) : (
              <Pressable
                onPress={() => setCreating(true)}
                style={{ padding: 12, borderRadius: 10, borderWidth: 1, borderColor: colors.border, borderStyle: "dashed" }}
              >
                <AppText style={{ color: colors.accent }}>+ 새 폴더 만들기</AppText>
              </Pressable>
            )}
          </ScrollView>
        </Pressable>
      </Pressable>
    </Modal>
  );
}
```

- [ ] **Step 2: 타입체크**

Run: `cd /Users/gimtaehyeong/Desktop/trova-app && npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add src/components/FolderPickerModal.tsx
git commit -m "feat: 폴더 선택/생성 모달 추가"
```

---

## Task 9: 앱 — `TripDetailScreen`의 ⭐를 `FolderPickerModal`에 연결

**Files:**
- Modify: `src/screens/TripDetailScreen.tsx`

**Interfaces:**
- Consumes: Task 8의 `FolderPickerModal`, Task 6의 `addBookmark(placeId, folderId)`

- [ ] **Step 1: 상태 추가**

`const [reviewTarget, ...] = useState(...)` 근처에 추가:

```tsx
  const [folderPickerPlaceId, setFolderPickerPlaceId] = useState<number | null>(null);
```

- [ ] **Step 2: `handleToggleBookmark` 수정**

기존:

```tsx
  async function handleToggleBookmark(placeId: number) {
    if (bookmarkedPlaceIds.has(placeId)) return;
    try {
      await addBookmark(placeId);
      await queryClient.invalidateQueries({ queryKey: ["bookmarks"] });
    } catch {
      setError("찜하기에 실패했어요.");
    }
  }
```

를 아래로 교체 — 실제 저장은 폴더를 고른 뒤 실행:

```tsx
  async function handleToggleBookmark(placeId: number) {
    if (bookmarkedPlaceIds.has(placeId)) return;
    setFolderPickerPlaceId(placeId);
  }

  async function handlePickFolder(folderId: number | null) {
    if (folderPickerPlaceId === null) return;
    const placeId = folderPickerPlaceId;
    setFolderPickerPlaceId(null);
    try {
      await addBookmark(placeId, folderId);
      await queryClient.invalidateQueries({ queryKey: ["bookmarks"] });
    } catch {
      setError("찜하기에 실패했어요.");
    }
  }
```

- [ ] **Step 3: import 추가**

```tsx
import { FolderPickerModal } from "@/components/FolderPickerModal";
```

- [ ] **Step 4: 모달 렌더링 추가**

`<PlaceReviewModal .../>` 바로 아래에 추가:

```tsx
          <FolderPickerModal
            visible={folderPickerPlaceId !== null}
            onClose={() => setFolderPickerPlaceId(null)}
            onPick={handlePickFolder}
          />
```

- [ ] **Step 5: 타입체크**

Run: `cd /Users/gimtaehyeong/Desktop/trova-app && npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 6: 수동 확인**

Metro 리로드 후, 여행 상세 → 검색 탭에서 장소 검색 → ⭐ 누르면
폴더 선택 모달이 뜨고, 폴더를 고르거나 새로 만들면 저장되는지 확인.

- [ ] **Step 7: 커밋**

```bash
git add src/screens/TripDetailScreen.tsx
git commit -m "feat: 여행 상세 화면 찜하기를 폴더 선택 모달에 연결"
```

---

## 이번 플랜에서 뺀 것

`moveBookmarkToFolder`/`deleteFolder`(Task 6, 11에서 API 클라이언트 함수는
만듦)를 실제로 누르는 UI 버튼은 이번 플랜에 없다 — "이미 찜한 걸 다른
폴더로 옮기기"와 "폴더 자체를 지우기"는 v1에서는 빼고, 필요하면 제거 후
원하는 폴더로 다시 저장하는 것으로 대체한다(탭 두 번으로 같은 결과).
API는 이미 만들어뒀으니 나중에 `SavedPlacesScreen`/`/bookmarks` 리스트
아이템에 버튼 하나만 추가하면 된다 — 지금 스코프를 더 키우지 않기 위한
의도적인 선택.

## Task 10: 앱 — `SavedPlacesScreen`(풀스크린 지도 + 바텀시트) + 내비게이션

**Files:**
- Create: `src/screens/SavedPlacesScreen.tsx`
- Modify: `src/navigation/types.ts`
- Modify: `src/navigation/RootNavigator.tsx`
- Modify: `src/screens/HomeScreen.tsx`
- Modify: `package.json`(의존성 추가)

**Interfaces:**
- Consumes: `InlineMap`(Task 7, `fill`/`showPath={false}`/`selectedId` 사용),
  `listFolders`/`listBookmarks`/`removeBookmark` (Task 6)

- [ ] **Step 1: `@gorhom/bottom-sheet` 설치**

```bash
cd /Users/gimtaehyeong/Desktop/trova-app && npx expo install @gorhom/bottom-sheet
```

순수 JS 패키지고 이미 설치된 `react-native-gesture-handler`/
`react-native-reanimated` 위에서 동작 — 새 네이티브 모듈이 아니라서
`pod install`/`expo run:ios` 재빌드가 필요 없다.

- [ ] **Step 2: `navigation/types.ts`에 라우트 추가**

```ts
export type RootStackParamList = {
  Login: undefined;
  Home: undefined;
  Processing: { jobId: number };
  PlacesList: undefined;
  VideoGroup: { jobId: number };
  TripsList: undefined;
  NewTrip: undefined;
  TripDetail: { id: number };
  SavedPlaces: undefined;
};
```

- [ ] **Step 3: `SavedPlacesScreen.tsx` 작성**

```tsx
import { useMemo, useState } from "react";
import { Pressable, View } from "react-native";
import BottomSheet, { BottomSheetFlatList } from "@gorhom/bottom-sheet";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AppText } from "@/components/AppText";
import { InlineMap } from "@/components/InlineMap";
import { listBookmarks, listFolders, removeBookmark, type Bookmark, type BookmarkFolder } from "@/lib/api/bookmarks";
import { colors } from "@/lib/theme";

const UNSORTED_ID = -1; // "미분류" 가상 폴더 id — 실제 폴더 id는 항상 양수(DB IDENTITY)라 겹치지 않는다.

export function SavedPlacesScreen() {
  const bookmarksQuery = useQuery({ queryKey: ["bookmarks"], queryFn: listBookmarks });
  const foldersQuery = useQuery({ queryKey: ["bookmarkFolders"], queryFn: listFolders });
  const queryClient = useQueryClient();
  const [activeFolderId, setActiveFolderId] = useState<number | null>(null); // null = 폴더 목록 보기
  const snapPoints = useMemo(() => ["18%", "55%", "90%"], []);

  const bookmarks = bookmarksQuery.data ?? [];
  const folders = foldersQuery.data ?? [];
  const folderColorById = new Map(folders.map((f) => [f.id, f.color]));

  const visibleBookmarks =
    activeFolderId === null
      ? bookmarks
      : activeFolderId === UNSORTED_ID
        ? bookmarks.filter((b) => b.folderId === null)
        : bookmarks.filter((b) => b.folderId === activeFolderId);

  const pins = visibleBookmarks
    .filter((b) => b.latitude !== null && b.longitude !== null)
    .map((b) => ({
      id: String(b.id),
      latitude: b.latitude as number,
      longitude: b.longitude as number,
      color: b.folderId !== null ? folderColorById.get(b.folderId) : colors.inkMuted,
    }));

  const unsortedCount = bookmarks.filter((b) => b.folderId === null).length;

  async function handleRemove(id: number) {
    await removeBookmark(id);
    await queryClient.invalidateQueries({ queryKey: ["bookmarks"] });
  }

  return (
    <View style={{ flex: 1 }}>
      <InlineMap pins={pins} fill showPath={false} />

      <BottomSheet index={1} snapPoints={snapPoints}>
        {activeFolderId === null ? (
          <BottomSheetFlatList
            data={[{ id: UNSORTED_ID, name: "미분류", color: colors.inkMuted, placeCount: unsortedCount }, ...folders]}
            keyExtractor={(item: BookmarkFolder) => String(item.id)}
            contentContainerStyle={{ padding: 16, gap: 12 }}
            ListHeaderComponent={
              <AppText weight="medium" style={{ fontSize: 16, marginBottom: 4 }}>
                저장 장소
              </AppText>
            }
            renderItem={({ item }: { item: BookmarkFolder }) => (
              <Pressable
                onPress={() => setActiveFolderId(item.id)}
                style={{
                  flexDirection: "row",
                  alignItems: "center",
                  gap: 10,
                  padding: 14,
                  borderRadius: 12,
                  borderWidth: 1,
                  borderColor: colors.border,
                }}
              >
                <View style={{ width: 14, height: 14, borderRadius: 7, backgroundColor: item.color }} />
                <AppText style={{ flex: 1 }}>{item.name}</AppText>
                <AppText style={{ fontSize: 12, color: colors.inkMuted }}>{item.placeCount}개</AppText>
              </Pressable>
            )}
          />
        ) : (
          <BottomSheetFlatList
            data={visibleBookmarks}
            keyExtractor={(item: Bookmark) => String(item.id)}
            contentContainerStyle={{ padding: 16, gap: 12 }}
            ListHeaderComponent={
              <Pressable onPress={() => setActiveFolderId(null)} style={{ marginBottom: 4 }}>
                <AppText style={{ color: colors.accent }}>← 폴더 목록</AppText>
              </Pressable>
            }
            ListEmptyComponent={
              <AppText style={{ color: colors.inkMuted, textAlign: "center", padding: 16 }}>
                이 폴더엔 아직 저장한 장소가 없어요.
              </AppText>
            }
            renderItem={({ item }: { item: Bookmark }) => (
              <View
                style={{
                  flexDirection: "row",
                  alignItems: "center",
                  justifyContent: "space-between",
                  padding: 12,
                  borderRadius: 12,
                  borderWidth: 1,
                  borderColor: colors.border,
                }}
              >
                <AppText style={{ flex: 1 }} numberOfLines={1}>
                  {item.placeName}
                </AppText>
                <Pressable onPress={() => handleRemove(item.id)}>
                  <AppText style={{ fontSize: 12, color: colors.inkMuted }}>제거</AppText>
                </Pressable>
              </View>
            )}
          />
        )}
      </BottomSheet>
    </View>
  );
}
```

- [ ] **Step 4: `RootNavigator.tsx`에 화면 등록**

```tsx
import { SavedPlacesScreen } from "@/screens/SavedPlacesScreen";
```

를 다른 스크린 import들 옆에 추가하고, `<Stack.Screen name="TripDetail" .../>` 아래에:

```tsx
          <Stack.Screen name="SavedPlaces" component={SavedPlacesScreen} options={{ title: "저장 장소" }} />
```

- [ ] **Step 5: `HomeScreen.tsx`에 진입 버튼 추가**

`"영상 기록 보기"` 버튼과 `"내 여행 보기"` 버튼 사이에 추가:

```tsx
      <Pressable
        onPress={() => navigation.navigate("SavedPlaces")}
        style={{ height: 48, borderRadius: 12, borderWidth: 1, borderColor: colors.border, justifyContent: "center", alignItems: "center" }}
      >
        <AppText weight="medium">저장 장소 보기</AppText>
      </Pressable>
```

- [ ] **Step 6: 타입체크**

Run: `cd /Users/gimtaehyeong/Desktop/trova-app && npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 7: 수동 확인**

Metro 리로드(순수 JS 패키지 추가라 native rebuild 불필요 — 다만
`@gorhom/bottom-sheet`가 새 패키지라 Metro를 `--clear`로 한 번 재시작하는
게 안전하다) 후, 홈 → "저장 장소 보기" → 지도+바텀시트가 뜨고, 폴더를
탭하면 그 폴더 장소 리스트로 전환되며 지도 핀이 그 폴더 장소들로
바뀌는지 확인.

- [ ] **Step 8: 커밋**

```bash
git add src/screens/SavedPlacesScreen.tsx src/navigation/types.ts \
        src/navigation/RootNavigator.tsx src/screens/HomeScreen.tsx \
        package.json package-lock.json
git commit -m "feat: 저장 장소 화면(지도+바텀시트+폴더) 추가"
```

---

## Task 11: 웹 — `api/bookmarks.ts`에 폴더 API 추가

**Files:**
- Modify: `src/lib/api/bookmarks.ts`

**Interfaces:**
- Produces: 앱과 동일한 타입/함수 시그니처(Task 6과 대칭) — `Bookmark.folderId`,
  `BookmarkFolder`, `listFolders`, `createFolder`, `deleteFolder`,
  `moveBookmarkToFolder`, `addBookmark(placeId, folderId?)`

- [ ] **Step 1: 파일 전체 교체**

```ts
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export type Bookmark = {
  id: number;
  placeId: number;
  placeName: string;
  googlePlaceId: string;
  mood: string | null;
  space: string | null;
  latitude: number | null;
  longitude: number | null;
  createdAt: string;
  folderId: number | null;
};

export type BookmarkFolder = {
  id: number;
  name: string;
  color: string;
  placeCount: number;
};

export async function listBookmarks(): Promise<Bookmark[]> {
  const res = await fetch(`${API_BASE_URL}/api/bookmarks`, { credentials: "include" });
  if (!res.ok) {
    throw new Error(`GET /api/bookmarks failed: ${res.status}`);
  }
  return res.json();
}

export async function addBookmark(placeId: number, folderId?: number | null): Promise<Bookmark> {
  const res = await fetch(`${API_BASE_URL}/api/bookmarks`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ placeId, folderId: folderId ?? null }),
  });
  if (!res.ok) {
    throw new Error(`POST /api/bookmarks failed: ${res.status}`);
  }
  return res.json();
}

export async function removeBookmark(id: number): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/api/bookmarks/${id}`, {
    method: "DELETE",
    credentials: "include",
  });
  if (!res.ok) {
    throw new Error(`DELETE /api/bookmarks/${id} failed: ${res.status}`);
  }
}

export async function moveBookmarkToFolder(bookmarkId: number, folderId: number | null): Promise<Bookmark> {
  const res = await fetch(`${API_BASE_URL}/api/bookmarks/${bookmarkId}`, {
    method: "PATCH",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ folderId }),
  });
  if (!res.ok) {
    throw new Error(`PATCH /api/bookmarks/${bookmarkId} failed: ${res.status}`);
  }
  return res.json();
}

export async function listFolders(): Promise<BookmarkFolder[]> {
  const res = await fetch(`${API_BASE_URL}/api/bookmarks/folders`, { credentials: "include" });
  if (!res.ok) {
    throw new Error(`GET /api/bookmarks/folders failed: ${res.status}`);
  }
  return res.json();
}

export async function createFolder(name: string, color: string): Promise<BookmarkFolder> {
  const res = await fetch(`${API_BASE_URL}/api/bookmarks/folders`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name, color }),
  });
  if (!res.ok) {
    throw new Error(`POST /api/bookmarks/folders failed: ${res.status}`);
  }
  return res.json();
}

export async function deleteFolder(id: number): Promise<void> {
  const res = await fetch(`${API_BASE_URL}/api/bookmarks/folders/${id}`, {
    method: "DELETE",
    credentials: "include",
  });
  if (!res.ok) {
    throw new Error(`DELETE /api/bookmarks/folders/${id} failed: ${res.status}`);
  }
}
```

- [ ] **Step 2: 타입체크**

Run: `cd /Users/gimtaehyeong/Desktop/trova-frontend && npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add src/lib/api/bookmarks.ts
git commit -m "feat: 찜 폴더 API 클라이언트 함수 추가"
```

---

## Task 12: 웹 — `FolderPickerModal` 컴포넌트

**Files:**
- Create: `src/components/FolderPickerModal.tsx`

**Interfaces:**
- Consumes: Task 11의 `listFolders`/`createFolder`
- Produces: `FolderPickerModal({visible, onClose, onPick}: {visible: boolean;
  onClose: () => void; onPick: (folderId: number | null) => void})` — 앱
  버전(Task 8)과 같은 인터페이스, 웹 스타일(Tailwind, 중앙 오버레이 —
  기존 `RecommendedPlaceCard`의 리뷰 모달과 같은 패턴)로만 다르게 구현

- [ ] **Step 1: 웹의 색상 팔레트 확인**

`trova-frontend/src/lib/itinerary.ts`의 `DAY_COLORS`를 그대로 재사용한다
(앱과 마찬가지로 새 색을 만들지 않는다). 이 배열은 export 안 되어 있으므로
export 처리:

`src/lib/itinerary.ts`에서:
```ts
const DAY_COLORS = ["#FF6B4A", "#4A90D9", "#4AC98F", "#D9A94A", "#9B6BD9", "#D94A8C"];
```
를
```ts
export const DAY_COLORS = ["#FF6B4A", "#4A90D9", "#4AC98F", "#D9A94A", "#9B6BD9", "#D94A8C"];
```
로 변경.

- [ ] **Step 2: 컴포넌트 작성**

```tsx
"use client";

import { useEffect, useState } from "react";
import { DAY_COLORS } from "@/lib/itinerary";
import { createFolder, listFolders, type BookmarkFolder } from "@/lib/api/bookmarks";

export function FolderPickerModal({
  visible,
  onClose,
  onPick,
}: {
  visible: boolean;
  onClose: () => void;
  onPick: (folderId: number | null) => void;
}) {
  const [folders, setFolders] = useState<BookmarkFolder[]>([]);
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState("");
  const [newColor, setNewColor] = useState<string>(DAY_COLORS[0]);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!visible) return;
    listFolders().then(setFolders);
  }, [visible]);

  async function handleCreateAndPick() {
    if (!newName.trim() || busy) return;
    setBusy(true);
    try {
      const folder = await createFolder(newName.trim(), newColor);
      setCreating(false);
      setNewName("");
      onPick(folder.id);
    } finally {
      setBusy(false);
    }
  }

  if (!visible) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <div
        className="w-full max-w-sm rounded-xl border border-border-subtle bg-bg p-5 shadow-lg"
        onClick={(e) => e.stopPropagation()}
      >
        <p className="mb-3 font-medium text-ink">어느 폴더에 저장할까요?</p>

        <div className="flex flex-col gap-2">
          <button
            type="button"
            onClick={() => onPick(null)}
            className="rounded-lg border border-border-subtle p-3 text-left text-sm text-ink hover:bg-bg-muted"
          >
            미분류로 저장
          </button>

          {folders.map((folder) => (
            <button
              key={folder.id}
              type="button"
              onClick={() => onPick(folder.id)}
              className="flex items-center gap-2 rounded-lg border border-border-subtle p-3 text-left text-sm hover:bg-bg-muted"
            >
              <span className="h-3 w-3 rounded-full" style={{ backgroundColor: folder.color }} />
              <span className="flex-1 text-ink">{folder.name}</span>
              <span className="text-xs text-ink-muted">{folder.placeCount}개</span>
            </button>
          ))}

          {creating ? (
            <div className="flex flex-col gap-2 rounded-lg border border-border-subtle p-3">
              <input
                autoFocus
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                placeholder="새 폴더 이름"
                className="rounded border border-border px-2 py-1.5 text-sm"
              />
              <div className="flex gap-2">
                {DAY_COLORS.map((color) => (
                  <button
                    key={color}
                    type="button"
                    onClick={() => setNewColor(color)}
                    style={{ backgroundColor: color }}
                    className={`h-6 w-6 rounded-full ${newColor === color ? "ring-2 ring-ink ring-offset-1" : ""}`}
                    aria-label={color}
                  />
                ))}
              </div>
              <button
                type="button"
                onClick={handleCreateAndPick}
                disabled={!newName.trim() || busy}
                className="rounded bg-accent px-3 py-2 text-sm font-medium text-white disabled:opacity-50"
              >
                만들고 저장
              </button>
            </div>
          ) : (
            <button
              type="button"
              onClick={() => setCreating(true)}
              className="rounded-lg border border-dashed border-border-subtle p-3 text-left text-sm text-accent hover:bg-bg-muted"
            >
              + 새 폴더 만들기
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 3: 타입체크**

Run: `cd /Users/gimtaehyeong/Desktop/trova-frontend && npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add src/components/FolderPickerModal.tsx src/lib/itinerary.ts
git commit -m "feat: 폴더 선택/생성 모달 추가"
```

---

## Task 13: 웹 — 찜 버튼 두 곳을 `FolderPickerModal`에 연결

**Files:**
- Modify: `src/app/discover/page.tsx`
- Modify: `src/components/TripDetailView.tsx`

**Interfaces:**
- Consumes: Task 12의 `FolderPickerModal`, Task 11의
  `addBookmark(placeId, folderId)`

- [ ] **Step 1: `discover/page.tsx` — 추가(찜 안 한 상태 → 찜) 경로만
  모달을 거치게 수정**

`import` 구역에 추가:

```tsx
import { FolderPickerModal } from "@/components/FolderPickerModal";
```

컴포넌트 상단 상태 선언부에 추가:

```tsx
  const [folderPickerPlaceId, setFolderPickerPlaceId] = useState<number | null>(null);
```

기존 `handleToggleBookmark`를 아래로 교체 — 제거 경로는 그대로 즉시
실행하고, 추가 경로만 모달을 띄운다:

```tsx
  async function handleToggleBookmark(place: RecommendedPlace) {
    if (pendingPlaceId !== null) return;
    const existingBookmarkId = bookmarksByPlaceId.get(place.id);
    if (existingBookmarkId !== undefined) {
      setPendingPlaceId(place.id);
      try {
        await removeBookmark(existingBookmarkId);
        setBookmarksByPlaceId((current) => {
          const next = new Map(current);
          next.delete(place.id);
          return next;
        });
      } catch {
        setError("찜하기에 실패했어요. 다시 시도해주세요.");
      } finally {
        setPendingPlaceId(null);
      }
      return;
    }
    setFolderPickerPlaceId(place.id);
  }

  async function handlePickFolder(folderId: number | null) {
    if (folderPickerPlaceId === null) return;
    const placeId = folderPickerPlaceId;
    setFolderPickerPlaceId(null);
    setPendingPlaceId(placeId);
    try {
      const created = await addBookmark(placeId, folderId);
      setBookmarksByPlaceId((current) => new Map(current).set(placeId, created.id));
    } catch {
      setError("찜하기에 실패했어요. 다시 시도해주세요.");
    } finally {
      setPendingPlaceId(null);
    }
  }
```

JSX 최상위 반환 구역(다른 모달/컴포넌트들 옆) 어딘가에 추가:

```tsx
      <FolderPickerModal
        visible={folderPickerPlaceId !== null}
        onClose={() => setFolderPickerPlaceId(null)}
        onPick={handlePickFolder}
      />
```

- [ ] **Step 2: `TripDetailView.tsx` — 동일 패턴 적용**

`import` 구역에 추가:

```tsx
import { FolderPickerModal } from "@/components/FolderPickerModal";
```

상태 선언부에 추가:

```tsx
  const [folderPickerPlaceId, setFolderPickerPlaceId] = useState<number | null>(null);
```

기존 `handleToggleBookmark`를 아래로 교체(이 화면은 원래 제거 경로가
없었다 — ⭐는 추가만 하고, 해제는 "찜한 장소" 탭의 별도 버튼으로 한다.
그 구조는 그대로 유지):

```tsx
  async function handleToggleBookmark(placeId: number) {
    if (bookmarkedPlaceIds.has(placeId)) return;
    setFolderPickerPlaceId(placeId);
  }

  async function handlePickFolder(folderId: number | null) {
    if (folderPickerPlaceId === null) return;
    const placeId = folderPickerPlaceId;
    setFolderPickerPlaceId(null);
    try {
      const created = await addBookmark(placeId, folderId);
      setBookmarkedPlaceIds((current) => new Set(current).add(placeId));
      setBookmarks((current) => [created, ...current]);
    } catch {
      setError("찜하기에 실패했어요.");
    }
  }
```

JSX 최상위 반환 구역에 추가:

```tsx
      <FolderPickerModal
        visible={folderPickerPlaceId !== null}
        onClose={() => setFolderPickerPlaceId(null)}
        onPick={handlePickFolder}
      />
```

- [ ] **Step 3: 타입체크 + 린트**

Run: `cd /Users/gimtaehyeong/Desktop/trova-frontend && npx tsc --noEmit && npm run lint`
Expected: 둘 다 에러 없음

- [ ] **Step 4: 수동 확인**

`npm run dev`(이미 떠 있으면 그대로) 후 브라우저에서 `/discover`와
여행 상세 페이지 양쪽에서 ☆를 누르면 폴더 선택 모달이 뜨는지, 폴더를
고르면 ⭐로 바뀌는지 확인.

- [ ] **Step 5: 커밋**

```bash
git add src/app/discover/page.tsx src/components/TripDetailView.tsx
git commit -m "feat: 찜하기를 폴더 선택 모달에 연결"
```

---

## Task 14: 웹 — `/bookmarks` 페이지 폴더 그룹핑

**Files:**
- Modify: `src/app/bookmarks/page.tsx`

**Interfaces:**
- Consumes: Task 11의 `listFolders`, `moveBookmarkToFolder`

- [ ] **Step 1: 페이지 전체 교체**

```tsx
"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useAuth } from "@/lib/auth/AuthContext";
import { LoadingProgress } from "@/components/LoadingProgress";
import { listBookmarks, listFolders, removeBookmark, type Bookmark, type BookmarkFolder } from "@/lib/api/bookmarks";

const MOOD_LABEL: Record<string, string> = {
  CALM: "차분함",
  LIVELY: "활기참",
  ROMANTIC: "로맨틱",
  TRENDY: "트렌디",
  COZY: "아늑함",
  LUXURIOUS: "고급스러움",
};

const UNSORTED_ID = -1;

export default function BookmarksPage() {
  const { user, loading: authLoading } = useAuth();
  const [bookmarks, setBookmarks] = useState<Bookmark[]>([]);
  const [folders, setFolders] = useState<BookmarkFolder[]>([]);
  const [activeFolderId, setActiveFolderId] = useState<number>(0); // 0 = 전체
  const [dataLoading, setDataLoading] = useState(true);
  const [removingId, setRemovingId] = useState<number | null>(null);

  useEffect(() => {
    if (authLoading || !user) return;
    Promise.all([listBookmarks(), listFolders()])
      .then(([b, f]) => {
        setBookmarks(b);
        setFolders(f);
      })
      .finally(() => setDataLoading(false));
  }, [authLoading, user]);

  async function handleRemove(id: number) {
    if (removingId !== null) return;
    setRemovingId(id);
    const previous = bookmarks;
    setBookmarks((current) => current.filter((b) => b.id !== id));
    try {
      await removeBookmark(id);
    } catch {
      setBookmarks(previous);
    } finally {
      setRemovingId(null);
    }
  }

  const loading = authLoading || (!!user && dataLoading);
  const unsortedCount = bookmarks.filter((b) => b.folderId === null).length;
  const visibleBookmarks =
    activeFolderId === 0
      ? bookmarks
      : activeFolderId === UNSORTED_ID
        ? bookmarks.filter((b) => b.folderId === null)
        : bookmarks.filter((b) => b.folderId === activeFolderId);

  return (
    <main className="mx-auto w-full max-w-3xl flex-1 px-6 py-10">
      <Link href="/discover" className="text-sm text-ink-muted hover:text-ink">
        ← 장소 추천
      </Link>

      <h1 className="mt-4 mb-6 text-xl font-semibold text-ink">찜한 장소</h1>

      {loading ? (
        <LoadingProgress />
      ) : !user ? (
        <p className="text-sm text-ink-muted">
          찜한 장소를 보려면{" "}
          <Link href="/login" className="font-medium text-accent hover:underline">
            로그인
          </Link>
          이 필요해요.
        </p>
      ) : bookmarks.length === 0 ? (
        <p className="text-sm text-ink-muted">아직 찜한 장소가 없어요.</p>
      ) : (
        <>
          {folders.length > 0 && (
            <div className="mb-6 flex flex-wrap gap-2">
              <button
                type="button"
                onClick={() => setActiveFolderId(0)}
                className={`rounded-full px-3 py-1.5 text-xs ${activeFolderId === 0 ? "bg-accent text-white" : "bg-bg-muted text-ink-muted"}`}
              >
                전체
              </button>
              {folders.map((folder) => (
                <button
                  key={folder.id}
                  type="button"
                  onClick={() => setActiveFolderId(folder.id)}
                  className={`flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs ${activeFolderId === folder.id ? "bg-accent text-white" : "bg-bg-muted text-ink-muted"}`}
                >
                  <span className="h-2 w-2 rounded-full" style={{ backgroundColor: folder.color }} />
                  {folder.name} ({folder.placeCount})
                </button>
              ))}
              <button
                type="button"
                onClick={() => setActiveFolderId(UNSORTED_ID)}
                className={`rounded-full px-3 py-1.5 text-xs ${activeFolderId === UNSORTED_ID ? "bg-accent text-white" : "bg-bg-muted text-ink-muted"}`}
              >
                미분류 ({unsortedCount})
              </button>
            </div>
          )}

          {visibleBookmarks.length === 0 ? (
            <p className="text-sm text-ink-muted">이 폴더엔 아직 저장한 장소가 없어요.</p>
          ) : (
            <ul className="flex flex-col gap-3">
              {visibleBookmarks.map((bookmark) => (
                <li
                  key={bookmark.id}
                  className="flex items-center justify-between gap-3 rounded-xl border border-border-subtle p-4"
                >
                  <div className="min-w-0">
                    <p className="truncate font-medium text-ink">{bookmark.placeName}</p>
                    {bookmark.mood && (
                      <p className="mt-0.5 text-xs text-ink-muted">
                        {MOOD_LABEL[bookmark.mood] ?? bookmark.mood}
                      </p>
                    )}
                  </div>
                  <button
                    type="button"
                    onClick={() => handleRemove(bookmark.id)}
                    disabled={removingId === bookmark.id}
                    className="shrink-0 text-xs text-ink-muted hover:text-accent disabled:opacity-40"
                  >
                    찜 해제
                  </button>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </main>
  );
}
```

- [ ] **Step 2: 타입체크 + 린트**

Run: `cd /Users/gimtaehyeong/Desktop/trova-frontend && npx tsc --noEmit && npm run lint`
Expected: 둘 다 에러 없음

- [ ] **Step 3: 수동 확인**

`/bookmarks` 페이지에서 폴더 탭이 뜨고, 탭 전환 시 목록이 즉시(재요청
없이) 필터링되는지 확인.

- [ ] **Step 4: 커밋**

```bash
git add src/app/bookmarks/page.tsx
git commit -m "feat: 찜한 장소 페이지에 폴더 그룹핑 추가"
```

---

## 최종 확인

모든 태스크 완료 후:
- [ ] 백엔드: `./gradlew test` 전체 통과
- [ ] 앱: `npx tsc --noEmit` 통과, 시뮬레이터에서 홈 → 저장 장소 → 폴더별
  지도/리스트 전환 확인, 여행 상세에서 ⭐ → 폴더 선택 → 저장 확인
- [ ] 웹: `npx tsc --noEmit && npm run lint` 통과, `/discover`·여행 상세·
  `/bookmarks` 세 곳 모두 폴더 플로우 확인
- [ ] 기존 회귀 없음: `TripDetailScreen`/`VideoGroupScreen`의 기존 지도들이
  여전히 동선을 그리고(showPath 기본값 true), `BookmarkServiceTest`의
  기존 5개 테스트가 그대로 통과
