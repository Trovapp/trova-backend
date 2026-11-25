# 같이 고치기 동시 수정 — 운영 서버 증거 (매니저 Mac, 읽기만)

창: 2026-11-25 16:09~17:10 KST. 스냅샷 before `20261125T160836`, after `20261125T171000`(KST, `prom/`). 프로세스 재시작 없음.
`journal.log`: `journalctl -u trova-backend` 16:09:00~17:10:30 KST 230줄, URL·이메일·토큰·user 번호 가림.
`http-diff.tsv`: 창 전후 `http_server_requests_seconds` 차분(메서드·URI·상태별 count, 평균 초). `/actuator` 제외.

## 요약 (http-diff.tsv)
| 요청 | 성공 | 충돌 409 | 기타 |
|---|---|---|---|
| PATCH 메모 `/api/trip-places/{id}/details` | 200×22 (평균 0.373초) | 20 (0.313초) | 404×8(삭제된 장소 수정) |
| PATCH 순서 `/api/trip-places/{id}/order` | 200×10 (0.466초) | 10 (0.375초) | |
| DELETE 장소 `/api/trip-places/{id}` | 204×8 (0.349초) | 2 (0.573초) | |
| GET 상세 `/api/trips/{id}` | 200×117 (0.087초) | | |
| GET 빈 시간 추천 | 200×44 | | 429×12 (하루 장소 조회 한도) |
| GET 장소 상세 `/api/trip-places/{id}/details` | | | 429×2 (같은 한도) |
| SSE `/api/trips/{id}/events` | 200×3 (평균 2,408초 연결) | | 이번 창 429 없음 |
| 인증 실패 `GET UNKNOWN` | | | **401×168, POST UNKNOWN 401×4** |

- 삭제 대 수정 2회(05·08)에서 장소가 남은 것은 DELETE 409 2건과 맞는다 — 수정이 먼저 반영되어 삭제가 버전 충돌로 거절됨(데이터 유실 아님). 나머지 8회는 DELETE 204 후 메모 PATCH 404.
- 메모 PATCH 409 20건 = 메모 10회 + 오프라인 복구 후 수동 재시도 10회와 대응하는지 대조 필요.
- **16:22:16~ 몇 초 동안 401이 169건 몰렸다**(journal `ApiErrors status=401`, URI가 UNKNOWN = 인증 단계에서 거절). 한 기기가 토큰 만료·오프라인 전환 직후 재시도를 반복한 것으로 보인다. 그 시각 두 기기 화면·adb logcat으로 어느 기기·어떤 요청인지 확인하고, 앱의 401 재시도(토큰 갱신 대기 없이 반복) 여부를 개선안에 넣어 주세요.
- `trova_trip_events_deferred_discarded_total`(capacity·expired) 0, `deferred_size` 0.
