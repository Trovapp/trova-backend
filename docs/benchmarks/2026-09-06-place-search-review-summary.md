# 장소 검색/리뷰요약 실측 (2026-09-06)

실제 브라우저에서 로그인 후 검증하며 관측한 수치(감으로 정한 값 아님 — Prometheus
`/actuator/prometheus`와 Postgres 직접 조회로 확인).

## 호출 횟수 및 지연시간

수동 검증 세션 동안 "경복궁" 검색 → 후보 중 하나("Gyeonghoeru Pavilion") 상세보기를
정확히 1번 클릭, 이후 같은 세션에서 재클릭(펼치기/접기)해도 API가 다시 호출되지
않는지 확인했다.

| 호출 | 횟수 | 지연시간(초) | 비고 |
|---|---|---|---|
| 텍스트 검색 (`GET /api/places/search`, 구글 Text Search) | 1회 | 측정 안 함(무료 티어, 로깅 대상 아님) | Basic+Pro 필드마스크만 요청, 과금 없음 |
| Place Details (`google-places`/`place-details`) | **1회** | 0.313초 | 유료 티어 — 캐시 미스일 때만 호출 |
| Gemini 리뷰요약 (`gemini`/`summarize_reviews`) | **1회** | 1.128초 | Details 호출과 1:1 대응 |

- 캐시 히트율 확인: 같은 장소의 "상세보기"를 여러 번 접었다 펼쳐도 Prometheus
  카운터가 정확히 1에서 멈춰있음 — `PlaceReviewService`의 "캐시 있으면 재호출 안
  함" 설계가 실제로 동작함을 확인.
- Prometheus 원본 지표(발췌):
  ```
  trova_api_call_seconds_count{operation="place-details",provider="google-places",success="true"} 1
  trova_api_call_seconds_sum{operation="place-details",provider="google-places",success="true"} 0.313
  trova_api_call_seconds_count{operation="summarize_reviews",provider="gemini",success="true"} 1
  trova_api_call_seconds_sum{operation="summarize_reviews",provider="gemini",success="true"} 1.128
  ```

## 데이터 영속성 확인 (Postgres 직접 조회)

`places` 테이블과 신설된 `place_review_snippets` 자식 테이블에 실제로 저장됐는지
JDBC로 직접 조회:

```
id=66 name=Gyeonghoeru Pavilion
  summary=경회루와 경복궁 경내는 연못에 비친 전통 건축물의 아름다움과 넓은
          산책로로 훌륭한 풍경과 사진 촬영 기회를 제공하며 방문객들에게
          하이라이트로 꼽힙니다. 다만, 경회루 내부가 닫혀 있어 들어갈 수
          없었다는 아쉬운 의견도 일부 존재합니다.
  snippets=[3개의 실제 구글 리뷰 원문 — 최대 3개로 정확히 제한됨]
```

## 화면 검증 (실제 클릭)

전체 플로우를 실제 브라우저 클릭으로 검증:

1. "경복궁" 검색 → 실제 구글 플레이스 후보 목록(평점·리뷰수 포함) 표시
2. "상세보기" 클릭 → 실제 Gemini 요약문 + 리뷰 원문 3개 인용구 표시
3. 하트 클릭 → 찜 처리, 같은 세션 내 "찜한 장소" 탭에 즉시 반영(버그 발견 후 수정 —
   아래 참고)
4. "추가" 클릭 → 여행 일정에 실제 장소 추가 확인
5. 추가된 장소 카드에서 방문시간(14:00~15:30)·이동수단(대중교통)·메모(경회루 사진
   찍기) 인라인 입력 → 페이지 새로고침 후에도 전부 유지됨을 확인

## 발견 및 수정한 버그

라이브 테스트 중 발견: 검색 탭에서 장소를 찜하면 하트 아이콘 상태(`bookmarkedPlaceIds`)만
갱신되고, "찜한 장소" 탭이 참조하는 `bookmarks` 배열은 갱신되지 않아 페이지를
새로고침하기 전까지 방금 찜한 장소가 탭에 나타나지 않는 문제가 있었다.
`addBookmark()`가 이미 생성된 `Bookmark`를 반환하므로, 그 값을 `bookmarks` 상태에도
같이 추가하도록 수정(커밋 `32a6411`, trova-frontend) — 수정 후 재검증하여 새로고침
없이 즉시 반영됨을 확인했다.

## 비용 관점 요약

- 구글 텍스트 검색: Basic+Pro 티어, 검색 1건당 무료(월 무료 크레딧 내에서 완전 무료
  등급)
- 구글 Place Details(+리뷰): 유료 티어, 실측 1회 호출에 0.313초 — "상세보기"를
  실제로 누른 장소에 대해서만, 그것도 캐시 미스일 때만 발생. 같은 장소를 여러
  사용자가 반복 조회해도 최초 1회만 과금 대상.
- Gemini 리뷰요약: 무료 티어(토큰당 과금 없음), 실측 1회 호출에 1.128초.
