# 40회 시도별 결과

시각은 KST 호스트 ADB dispatch이다. 응답 A/B는 시각별 오류 로그·구간별 HTTP 집계·화면 결과를 대조한 **귀속 추정**이며, 요청 ID를 기기에 연결한 패킷 기록은 없다. 버전 숫자·activity 행은 40회 모두 미확인이다. 서버 집계는 별도 창 전체 요청을 포함한다.

수렴 상한은 두 화면에 동일한 저장값/순서/삭제가 처음 확인된 캡처의 **종료 시각 − 빠른 dispatch**이다. 실제 완료 시간보다 큰 관측 상한이며 API 지연이나 SSE p95가 아니다. 초안은 저장값과 별도로 남을 수 있다. OCR은 원본 스크린샷과 함께 검토한다.

| 시도 | A 입력 | B 입력 | 최종 저장 상태 | 응답 A/B 추정 | 수렴 관측 | 버전·activity |
|---|---|---|---|---|---|---|
| memo-01 [A](observations/memo-01/after-10s-5584.png)·[B](observations/memo-01/after-10s-5586.png) | 16:10:09.132 | 16:10:09.132 | B-memo-01 | 409/200 | ≤2.241초 | 미확인 |
| memo-02 [A](observations/memo-02/after-10s-5584.png)·[B](observations/memo-02/after-10s-5586.png) | 16:11:14.788 | 16:11:14.788 | B-memo-02 | 409/200 | ≤3.609초 | 미확인 |
| memo-03 [A](observations/memo-03/after-10s-5584.png)·[B](observations/memo-03/after-10s-5586.png) | 16:12:24.524 | 16:12:24.524 | B-memo-03 | 409/200 | ≤1.979초 | 미확인 |
| memo-04 [A](observations/memo-04/after-10s-5584.png)·[B](observations/memo-04/after-10s-5586.png) | 16:13:37.001 | 16:13:37.001 | B-memo-04 | 409/200 | ≤2.038초 | 미확인 |
| memo-05 [A](observations/memo-05/after-10s-5584.png)·[B](observations/memo-05/after-10s-5586.png) | 16:14:23.154 | 16:14:23.154 | B-memo-05 | 409/200 | ≤1.982초 | 미확인 |
| memo-06 [A](observations/memo-06/after-10s-5584.png)·[B](observations/memo-06/after-10s-5586.png) | 16:15:05.369 | 16:15:05.369 | A-memo-06 | 200/409 | ≤1.851초 | 미확인 |
| memo-07 [A](observations/memo-07/after-10s-5584.png)·[B](observations/memo-07/after-10s-5586.png) | 16:16:52.676 | 16:16:52.676 | B-memo-07 | 409/200 | ≤3.812초 | 미확인 |
| memo-08 [A](observations/memo-08/after-10s-5584.png)·[B](observations/memo-08/after-10s-5586.png) | 16:17:48.544 | 16:17:48.544 | B-memo-08 | 409/200 | ≤2.099초 | 미확인 |
| memo-09 [A](observations/memo-09/after-10s-5584.png)·[B](observations/memo-09/after-10s-5586.png) | 16:18:41.720 | 16:18:41.720 | B-memo-09 | 409/200 | ≤3.817초 | 미확인 |
| memo-10 [A](observations/memo-10/after-10s-5584.png)·[B](observations/memo-10/after-10s-5586.png) | 16:19:31.000 | 16:19:31.000 | B-memo-10 | 409/200 | ≤2.339초 | 미확인 |
| order-01 [A](observations/order-01/after-10s-5584.png)·[B](observations/order-01/after-10s-5586.png) | 16:20:46.660 | 16:20:46.659 | T,B,L | 409/200 | ≤2.492초 | 미확인 |
| order-02 [A](observations/order-02/after-10s-5584.png)·[B](observations/order-02/after-10s-5586.png) | 16:21:44.560 | 16:21:44.560 | T,L,B | 409/200 | ≤2.760초 | 미확인 |
| order-03 [A](observations/order-03/after-10s-5584.png)·[B](observations/order-03/after-10s-5586.png) | 16:22:28.295 | 16:22:28.295 | L,T,B | 409/200 | ≤2.707초 | 미확인 |
| order-04 [A](observations/order-04/after-10s-5584.png)·[B](observations/order-04/after-10s-5586.png) | 16:23:16.388 | 16:23:16.388 | T,L,B | 409/200 | ≤1.915초 | 미확인 |
| order-05 [A](observations/order-05/after-10s-5584.png)·[B](observations/order-05/after-10s-5586.png) | 16:24:20.117 | 16:24:20.117 | T,B,L | 409/200 | ≤2.237초 | 미확인 |
| order-06 [A](observations/order-06/after-10s-5584.png)·[B](observations/order-06/after-10s-5586.png) | 16:24:57.752 | 16:24:57.752 | T,L,B | 409/200 | ≤2.291초 | 미확인 |
| order-07 [A](observations/order-07/after-10s-5584.png)·[B](observations/order-07/after-10s-5586.png) | 16:26:01.622 | 16:26:01.621 | T,B,L | 409/200 | ≤3.704초 | 미확인 |
| order-08 [A](observations/order-08/after-10s-5584.png)·[B](observations/order-08/after-10s-5586.png) | 16:26:40.875 | 16:26:40.875 | T,L,B | 409/200 | ≤3.060초 | 미확인 |
| order-09 [A](observations/order-09/after-10s-5584.png)·[B](observations/order-09/after-10s-5586.png) | 16:27:32.920 | 16:27:32.920 | T,B,L | 409/200 | ≤2.684초 | 미확인 |
| order-10 [A](observations/order-10/after-10s-5584.png)·[B](observations/order-10/after-10s-5586.png) | 16:28:15.018 | 16:28:15.018 | T,L,B | 409/200 | ≤3.643초 | 미확인 |
| delete-01 [A](observations/delete-01/after-10s-5584.png)·[B](observations/delete-01/after-10s-5586.png) | 16:30:58.253 | 16:30:58.253 | 장소 삭제 | 404/204 | ≤3.966초 | 미확인 |
| delete-02 [A](observations/delete-02/after-10s-5584.png)·[B](observations/delete-02/after-10s-5586.png) | 16:32:47.230 | 16:32:47.230 | 장소 삭제 | 404/204 | ≤4.133초 | 미확인 |
| delete-03 [A](observations/delete-03/after-10s-5584.png)·[B](observations/delete-03/after-10s-5586.png) | 16:34:08.013 | 16:34:08.013 | 장소 삭제 | 404/204 | ≤4.139초 | 미확인 |
| delete-04 [A](observations/delete-04/after-10s-5584.png)·[B](observations/delete-04/after-10s-5586.png) | 16:35:34.148 | 16:35:34.148 | 장소 삭제 | 404/204 | ≤4.091초 | 미확인 |
| delete-05 [A](observations/delete-05/after-10s-5584.png)·[B](observations/delete-05/after-10s-5586.png) | 16:36:56.471 | 16:36:56.471 | A-delete-05; 장소 유지 | 200/409 | ≤3.736초 | 미확인 |
| delete-06 [A](observations/delete-06/after-10s-5584.png)·[B](observations/delete-06/after-10s-5586.png) | 16:38:26.479 | 16:38:26.479 | 장소 삭제 | 404/204 | ≤5.002초 | 미확인 |
| delete-07 [A](observations/delete-07/after-10s-5584.png)·[B](observations/delete-07/after-10s-5586.png) | 16:42:34.190 | 16:42:34.190 | 장소 삭제 | 404/204 | ≤4.246초 | 미확인 |
| delete-08 [A](observations/delete-08/after-10s-5584.png)·[B](observations/delete-08/after-10s-5586.png) | 16:44:29.228 | 16:44:29.228 | A-delete-08; 장소 유지 | 200/409 | ≤4.118초 | 미확인 |
| delete-09 [A](observations/delete-09/after-10s-5584.png)·[B](observations/delete-09/after-10s-5586.png) | 16:45:23.080 | 16:45:23.080 | 장소 삭제 | 404/204 | ≤4.136초 | 미확인 |
| delete-10 [A](observations/delete-10/after-10s-5584.png)·[B](observations/delete-10/after-10s-5586.png) | 16:46:49.685 | 16:46:49.685 | 장소 삭제 | 404/204 | ≤3.908초 | 미확인 |
| offline-01 [A](observations/offline-01/after-10s-5584.png)·[B](observations/offline-01/after-10s-5586.png) | 16:48:39.638 | 16:48:39.638 | B-offline-01; A 초안 유지 | 오프라인 실패→수동409 / 200 | >20초; 수동 재시도 후 일치 | 미확인 |
| offline-02 [A](observations/offline-02/after-10s-5584.png)·[B](observations/offline-02/after-10s-5586.png) | 16:51:03.437 | 16:51:03.437 | B-offline-02; A 초안 유지 | 오프라인 실패→수동409 / 200 | >20초; 수동 재시도 후 일치 | 미확인 |
| offline-03 [A](observations/offline-03/after-10s-5584.png)·[B](observations/offline-03/after-10s-5586.png) | 16:52:54.731 | 16:52:54.731 | B-offline-03; A 초안 유지 | 오프라인 실패→수동409 / 200 | >20초; 수동 재시도 후 일치 | 미확인 |
| offline-04 [A](observations/offline-04/after-10s-5584.png)·[B](observations/offline-04/after-10s-5586.png) | 16:54:37.665 | 16:54:37.665 | B-offline-04; A 초안 유지 | 오프라인 실패→수동409 / 200 | >20초; 수동 재시도 후 일치 | 미확인 |
| offline-05 [A](observations/offline-05/after-10s-5584.png)·[B](observations/offline-05/after-10s-5586.png) | 16:56:30.108 | 16:56:30.108 | B-offline-05; A 초안 유지 | 오프라인 실패→수동409 / 200 | >20초; 수동 재시도 후 일치 | 미확인 |
| offline-06 [A](observations/offline-06/after-10s-5584.png)·[B](observations/offline-06/after-10s-5586.png) | 16:58:30.002 | 16:58:30.002 | B-offline-06; A 초안 유지 | 오프라인 실패→수동409 / 200 | >20초; 수동 재시도 후 일치 | 미확인 |
| offline-07 [A](observations/offline-07/after-10s-5584.png)·[B](observations/offline-07/after-10s-5586.png) | 17:00:13.743 | 17:00:13.743 | B-offline-07; A 초안 유지 | 오프라인 실패→수동409 / 200 | >20초; 수동 재시도 후 일치 | 미확인 |
| offline-08 [A](observations/offline-08/after-10s-5584.png)·[B](observations/offline-08/after-10s-5586.png) | 17:02:31.229 | 17:02:31.229 | B-offline-08; A 초안 유지 | 오프라인 실패→수동409 / 200 | >20초; 수동 재시도 후 일치 | 미확인 |
| offline-09 [A](observations/offline-09/after-10s-5584.png)·[B](observations/offline-09/after-10s-5586.png) | 17:04:32.644 | 17:04:32.644 | B-offline-09; A 초안 유지 | 오프라인 실패→수동409 / 200 | >20초; 수동 재시도 후 일치 | 미확인 |
| offline-10 [A](observations/offline-10/after-10s-5584.png)·[B](observations/offline-10/after-10s-5586.png) | 17:06:13.333 | 17:06:13.333 | B-offline-10; A 초안 유지 | 오프라인 실패→수동409 / 200 | >60초(추가 관찰) | 미확인 |

순서 기호: T=이삭토스트 명동성당점, L=연화정 도서관, B=광안리해수욕장. offline 표의 409는 연결 복구 후 별도 수동 저장 재시도이며 최초 오프라인 저장의 HTTP 응답이 아니다.

삭제 05·08의 B에서는 두 건 모두 after-03s 캡처에서 친구가 방금 바꿨다는 충돌 안내를 확인했다. 1초 캡처만으로 안내가 없다고 결론 내리지 않는다.
