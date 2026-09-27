# 영상 장소 목록 API N+1 개선 실측 (2026-09-27, #29)

## 측정 방법
- 대상: `GET /api/places` (실사용 계정, 영상 장소 94곳·처리 작업 약 20개)
- 로컬 서버(Spring Boot, macOS)를 같은 Supabase DB에 연결해 실행
- SQL 수: `--logging.level.org.hibernate.SQL=DEBUG`로 요청 1회 동안 찍힌 SQL 로그 줄 수
- 응답 시간: 워밍업 1회 후 5회 호출의 중앙값·최대값(클라이언트 측 측정, 로컬 → Supabase 왕복 포함)

## 결과

| | 수정 전 | 수정 후 |
|---|---|---|
| 요청 1회당 SQL 수 | 22개 | 2개 |
| 응답 시간 중앙값 | 362ms | 106ms |
| 응답 시간 최대 | 422ms | 225ms |

- 수정 전 22개 = 사용자 조회 1 + 목록 1 + `processing_jobs … join users` 처리 작업마다 1개(20)
- 수정 후 2개 = 사용자 조회 1 + 목록(처리 작업·사용자 조인) 1
- 비교 기준: 같은 조건에서 `/api/trips` 3개·중앙값 51ms, `/api/bookmarks` 8개·125ms

## 원인과 수정
`SavedPlace`의 `@ManyToOne`(기본 EAGER)만으로는 목록 조회 뒤 처리 작업을 작업마다 따로 가져왔다.
`SavedPlaceRepository.findByUserOrderByCreatedAtDescIdDesc`에 `@EntityGraph(processingJob, processingJob.user, user)`를 붙여 한 쿼리로 조인.
회귀 방지: `SavedPlaceListQueryCountTest` — 처리 작업 5개일 때 준비된 SQL 문이 1개인지 Hibernate Statistics로 검증(수정 전 6개).

## 한계
- 응답 시간은 로컬 → Supabase 네트워크 지연을 포함한 값이라 배포 서버(Oracle E2.1.Micro)에서의 수치와 다를 수 있다.
