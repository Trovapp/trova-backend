# 찜한 장소 폴더링 설계

날짜: 2026-09-07
상태: 승인됨 (브레인스토밍 완료)

## 배경

현재 `Bookmark`는 `(user, place)` 단순 1:1 관계라 폴더/분류 개념이 전혀 없다.
사용자가 네이버 지도의 "저장" 기능(장소를 폴더별 목록으로 정리하고, 지도에서
색상으로 구분해 보는 방식)을 참고해 앱에도 같은 걸 넣어달라고 요청했다.

네이버 지도 조사 결과([App Store 설명](https://apps.apple.com/kr/app/%EB%84%A4%EC%9D%B4%EB%B2%84%EC%A7%80%EB%8F%84-%EC%9E%A5%EC%86%8C%EC%9D%98-%EB%B0%9C%EA%B2%AC%EA%B3%BC-%EC%98%88%EC%95%BD-%EB%82%B4%EB%B9%84%EA%B2%8C%EC%9D%B4%EC%85%98/id311867728), 사용 후기):
- 장소를 저장할 때 기존 목록에 추가하거나 새 목록(폴더)을 만들 수 있다.
- 새 목록은 지도에 표시될 색상을 지정할 수 있다.
- "저장" 탭에 들어가면 지도가 뜨고, 그 위로 저장 목록(폴더)들을 보여주는
  바텀시트가 있다. 폴더를 고르면 그 폴더의 장소만 지도에 표시된다.
- 모바일 UX 리서치([NN/G](https://www.nngroup.com/articles/bottom-sheet/),
  [Digia](https://www.digia.tech/post/bottom-sheets-vs-modals-interruption-layer/))
  결과, 모바일에서는 중앙 팝업보다 바텀시트가 일관되게 권장된다 — 이 프로젝트가
  이미 장소 상세를 바텀시트로 구현한 것과도 같은 방향.

## 범위

**포함**:
- 백엔드: `BookmarkFolder` 엔티티 + `Bookmark.folder_id` + 폴더 CRUD/할당 API
- 앱: "저장 장소" 신규 화면(풀스크린 지도 + 바텀시트로 폴더 목록/장소 목록),
  ⭐ 찜하기 시 폴더 선택 모달
- 웹: `/bookmarks` 페이지에 폴더별 그룹핑 리스트 추가(지도 없이)
- 찜 아이콘을 하트(❤️/🤍)에서 별(⭐/☆)로 교체 — "좋아요"보다 "저장" 의미에
  맞게. 앱/웹 모두 적용 완료(이 스펙과 별개로 이미 반영됨).
- 기존 "저장한 장소"(영상 추출 전체 목록 + 처리 대기/실패 상태 화면)는
  이름만 "영상 기록"으로 변경해 새 "저장 장소"(찜)와 구분 — 기능은 유지
  (이미 반영됨).

**비목표**:
- 폴더 공유/협업 기능 (네이버 지도엔 있지만 요청 범위 밖)
- 하나의 찜이 여러 폴더에 동시에 속하는 다대다 구조 — 네이버 지도도
  기본적으로 장소 하나는 하나의 목록에 속하고, 우리 쪽도 그게 단순하고
  충분하다.
- 오프라인 캐싱

## 결정 사항

### 데이터 모델

`BookmarkFolder` 엔티티 신설:
```java
@Entity
@Table(name = "bookmark_folders")
public class BookmarkFolder {
    Long id;
    User user;       // @ManyToOne
    String name;
    String color;    // hex, 지도 핀 색상으로 사용
    LocalDateTime createdAt;
}
```

`Bookmark`에 `folder_id`(nullable FK, `BookmarkFolder`) 추가. `null`이면
"미분류"로 취급(네이버 지도의 기본 "저장됨" 목록과 동일한 역할 — 별도의
"기본 폴더" row를 DB에 만들지 않고 null로 표현해 폴더 삭제 시 소속 찜이
고아가 되는 문제를 피한다).

`BookmarkService.addBookmark`의 mood 선호도 점수 상승 부수효과는 그대로
유지 — 폴더링과 무관한 별개 로직이라 건드리지 않는다.

### API

- `POST /api/bookmarks/folders` `{name, color}` → 폴더 생성
- `GET /api/bookmarks/folders` → 내 폴더 목록(각 폴더의 장소 개수 포함)
- `DELETE /api/bookmarks/folders/{id}` → 폴더 삭제. 소속 찜은 삭제하지
  않고 `folder_id`를 `null`로 되돌려 미분류로 남긴다(찜 자체가 없어지면
  안 됨).
- `PATCH /api/bookmarks/{id}` `{folderId}` → 찜의 폴더 재할당(`null`이면
  미분류로 이동)
- `POST /api/bookmarks` 기존 요청 바디에 선택적 `folderId` 추가(없으면
  미분류로 저장)
- `GET /api/bookmarks` 응답에 `folderId` 필드 추가. 서버 쪽 필터 파라미터는
  만들지 않는다 — 개인 찜 목록은 양이 적어서, 폴더별 보기는 클라이언트가
  전체 목록을 한 번 받아 `folderId`로 걸러내는 쪽이 폴더 전환마다 재요청이
  없어 더 매끄럽다.

### 앱 UX — "저장 장소" 화면(신규)

- 홈 화면에 진입점 추가(아직 없음 — 지금은 여행 상세 탭 안에서만 접근
  가능).
- 화면 진입 시 풀스크린 지도가 뜨고, 찜한 장소 전체가 소속 폴더 색상의
  핀으로 표시된다. 지금 있는 `InlineMap`(임베드용, 고정 높이)과는 다른
  풀스크린 지도가 필요 — `InlineMap`을 확장하기보다 `kakaoMapHtml.ts`를
  공유하는 새 풀스크린 지도 화면(전체 화면 WebView)으로 만든다.
- 하단에서 드래그 가능한 바텀시트가 폴더 목록(이름·색상 점·장소 개수)을
  보여준다.
- 폴더를 탭하면 시트가 그 폴더의 장소 리스트로 전환되고, 지도는 그 폴더
  핀들로 필터링 + 첫 핀으로 `panTo`.
- 장소 카드(검색 결과, 여행 상세)에서 ⭐를 누르면 폴더 선택 모달이 뜬다
  — 기존 폴더 목록 + "새 폴더 만들기"(이름+색상 입력). 네이버 지도와
  동일하게 저장 시점에 바로 분류한다(저장 후 나중에 옮기게 하지 않음).

### 웹 UX — `/bookmarks` 페이지 확장

지도+바텀시트는 모바일 전용 패턴이라 웹엔 넣지 않는다(기존에도 지도가
없었고, 지금 페이지 구조상 새로 넣는 게 과함 — "같은 디자인, 편한 UX는
플랫폼에 맞게"라는 이번 세션의 원칙과 일치). 대신:
- 페이지 상단(또는 좌측)에 폴더 탭/목록을 추가.
- 선택한 폴더의 장소만 기존과 같은 카드 리스트로 보여준다.
- 폴더가 없으면(전부 미분류) 기존과 동일하게 평평한 리스트.

## 아키텍처

### 백엔드 파일

- `entity/BookmarkFolder.java` (신규)
- `repository/BookmarkFolderRepository.java` (신규) — `findByUser`,
  `findByIdAndUser`
- `repository/BookmarkRepository.java` — `findByUserAndFolderOrderByCreatedAtDesc`
  추가
- `service/BookmarkService.java` — `createFolder`, `listFolders`(개수 포함),
  `deleteFolder`(소속 찜을 미분류로 되돌린 뒤 폴더 삭제), `moveToFolder`
  추가. 기존 `addBookmark`에 선택적 `folderId` 파라미터 추가.
- `controller/BookmarkController.java` — 위 API 엔드포인트 추가

### 앱 파일

- `src/screens/SavedPlacesScreen.tsx` (신규) — 풀스크린 지도 + 바텀시트
- `src/components/FolderPickerModal.tsx` (신규) — ⭐ 저장 시 폴더 선택
- `src/lib/api/bookmarks.ts` — `createFolder`, `listFolders`, `moveBookmark`
  추가, `addBookmark`에 `folderId` 선택 인자 추가
- `src/navigation/types.ts` / `RootNavigator.tsx` — `SavedPlaces` 라우트 추가,
  `HomeScreen`에 진입 버튼 추가
- 바텀시트는 기존에 쓰던 라이브러리가 없으므로, 이미 설치된
  `react-native-gesture-handler`/`react-native-reanimated` 기반으로 직접
  구현하거나(패널을 `PanGestureHandler` + `Animated`로 드래그), 필요하면
  `@gorhom/bottom-sheet`(MIT, 무료) 추가 설치 — 구현 단계에서 실제로 얼마나
  손이 가는지 보고 결정한다.

### 웹 파일

- `src/app/bookmarks/page.tsx` — 폴더 탭 UI 추가
- `src/lib/api/bookmarks.ts` — 위와 동일한 API 함수 추가
- `src/components/FolderPickerModal.tsx` (신규, 웹 버전) — 검색결과/여행상세
  카드에서 ⭐ 누를 때

## 테스트

- 백엔드: `BookmarkServiceTest`에 `createFolder`, `moveToFolder`, 폴더
  삭제 시 소속 찜이 미분류(`folder=null`)로 남는지 케이스 추가
- 기존 `addBookmark`의 mood 선호도 점수 테스트는 folderId 유무와 무관하게
  그대로 통과해야 함(회귀 확인)
