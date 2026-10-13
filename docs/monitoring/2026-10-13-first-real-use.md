# 2026-10-13 첫 실사용 영상 분석 — 운영 모니터링 기록

- 환경: **운영 서버**(Oracle E2.1.Micro, main f57472d) + 운영 DB. 사용자가 아이폰 PWA(app.trovapp.workers.dev, 앱 `feat/#7-pwa`)로 직접 사용.
- 수집: Mac의 Prometheus가 SSH 터널로 15초마다(ADR 0015), 서버 로그(journalctl), 운영 DB 읽기 쿼리. 지표는 서버 재시작(약 12:05 KST) 이후 누적값이라 이 사용자 한 명의 사용분이다.
- 대상 작업: ProcessingJob 73, 유튜브 쇼츠 "서울인데 서울 같지 않아서 좋은 곳", 자동 초안 TripDraft 6.

## 분석 흐름(서버 로그 시각, KST)

| 시각 | 단계 | 걸린 시간 |
|---|---|---|
| 14:12:20 | `POST /api/shares` → 202, `pipeline-1` 스레드에서 시작 | 응답 1.16초 |
| 14:12:20 → 14:12:29.5 | 자막·음성 받기 + Gemini 장소 추출(7곳) | 9.1초 |
| → 14:12:31.6 | 카카오 지오코딩 7곳("용출정"은 이름 매칭 실패 → 지역만으로 재검색) | 1.8초 |
| → 14:12:34.4 | 후보 4곳 재검토, 1곳 검증 → "용출정" 검증 실패로 좌표 폐기 | 2.7초 |
| 14:12:35.4 | DONE — 좌표 6곳, "위치 확인 안됨" 1곳(ADR 0006) | **공유부터 15.1초** |
| → 14:12:38.7 | 자동 일정 초안(`plan-1`, Gemini 1번, 영업시간 조회 0번) | 3.3초 |
| 14:16:08 | 초안 승인 `POST /api/trip-drafts/{id}/approve` | 0.81초 |
| 14:17:23 | 영상 장소로 일정 생성(`POST /api/places/videos/{jobId}/itinerary` 202) | — |

## API 요청(재시작 이후, 액추에이터 제외)

| API | 응답 | 횟수 | 평균 |
|---|---|---|---|
| GET /api/places/pending | 200 | 22 | 0.319초 |
| GET /api/notifications | 200 | 16 | 0.229초 |
| GET /api/bookmarks | 200 | 15 | 0.512초 |
| GET /api/places | 200 | 15 | 0.401초 |
| GET /api/trip-drafts/auto | 200 | 15 | **0.797초**(최대 1.44초) |
| GET /api/trips | 200 | 13 | 0.359초 |
| GET /api/places/videos/{jobId}/trip | **404** | 7 | 0.475초 |
| GET /api/places/videos/{jobId}/trip | 200 | 3 | 0.506초 |
| GET /api/trips/{id} | 200 | 7 | 0.398초 |
| GET /api/bookmarks/folders | 200 | 6 | 0.287초 |
| GET /api/trips/{tripId}/days/{day}/gap-recommendations | 200 | 5 | 0.424초 |
| GET /api/trip-drafts/{id} | 200 | 2 | 0.165초 |
| PATCH /api/trip-places/{id}/order | 200 | 2 | 0.274초 |
| GET /api/auth/me, POST /api/shares, POST …/approve, POST …/itinerary | 200·202 | 각 1 | 0.70·1.16·0.81·0.16초 |

메모 저장(`PATCH /api/trip-places/{id}` 상세 수정)은 **0건**.

## 서버 자원(최근 1시간 최댓값)

| 지표 | 값 |
|---|---|
| 힙 사용 | 72.8MB / 최대 371MB |
| CPU | 프로세스 18.9%, 서버 전체 8.9% |
| DB 연결(Hikari) | 사용 최대 6 / 10, 대기 0 |
| 비동기 스레드 | pipeline·plan 동시 최대 1, 대기 0 |
| GC 멈춤 합 | 0.287초 |
| 로그 WARN·ERROR | 0 |

## 찾은 것

1. **메모 "완료"를 눌러도 저장 요청이 서버에 가지 않았다** — 사용자가 "메모 수정시 완료가 안눌림"이라고 한 것과 일치. 앱 코드에서 "완료"는 `Keyboard.dismiss()`만 하고 저장은 입력창 blur에 맡기는데, 아이폰 웹에서는 글자 버튼을 눌러도 blur가 일어나지 않는다(앱에서 고친다).
2. `GET /api/places/videos/{jobId}/trip`이 "아직 여행 없음"을 404로 알려 7번 반복 — 정상 흐름인데 오류처럼 기록된다. 200 + 빈 값으로 바꿀지 후보.
3. `GET /api/trip-drafts/auto`가 다른 목록 API(0.2~0.5초)보다 느리고(평균 0.8초) 홈에 올 때마다 불린다 — 다음 성능 개선 후보.
4. 서버 자원은 이 사용량에서 여유가 크다(힙 20%, CPU 19%) — 동시 사용자 수에 따른 한계는 2026-09-21 부하 테스트(30 VU에서 처리량 포화)를 기준으로 본다.

## 다시 뽑는 법

```bash
# 정확한 누적값(재시작 이후): 터널이 열려 있을 때
curl -s localhost:18080/actuator/prometheus | grep '^http_server_requests_seconds_count'
# 1시간 최댓값 예
curl -s -G localhost:9090/api/v1/query --data-urlencode 'query=max_over_time(process_cpu_usage{job="trova-prod"}[1h])'
```
Prometheus `increase()`는 재시작 직후 처음 생긴 시계열의 첫 증가를 빼고 보정값을 내므로, 횟수는 원본 카운터로 셌다.
