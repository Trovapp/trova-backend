# 부하 테스트 Grafana 그래프 캡처 (2026-11-11 저장, #386)

Grafana Cloud 체험판이 끝나면 지표를 14일만 보관해 11-08~11-11 측정 그래프가 사라진다. 대시보드 "Trova 필수 모니터링"(v5)의
패널을 측정 시간대로 열어 캡처했다(시각은 KST, 운영 서버 job=`trova-prod`). 수치 원자료는 각 회차 폴더(`samples.jsonl`·`browse.json`)에 있다.

| 파일 | 패널 | 회차·시간 | 보여 주는 것 |
|---|---|---|---|
| `1-r15-384m-reels-latency.jpg` | 응답 시간(평균·p95·p99) | r15, 힙 384MiB, 11-10 22:25~23:00 | 영상 분석 + 조회. p95 2~4초, 22:39~22:41·22:45~22:58 지표가 비었다(앱이 지표를 못 보낼 만큼 멈춤). 조회 오류 48.4% |
| `2-r26-144m-reels-latency.jpg` | 응답 시간 | r26, 힙 144MiB, 11-11 19:00~19:25 | 같은 측정. 빈 구간 없이 p95 2.7초에서 1초로 내려옴. 조회 오류 0.7% |
| `3-r15-384m-reels-heap.jpg` | 힙 메모리 | r15 | 힙 committed가 120 → 168 → 248 → 300MiB로 커짐(G1 힙 확장, ADR 0040의 원인) |
| `4-r26-144m-reels-heap.jpg` | 힙 메모리 | r26 | 상한 144MiB, committed 118MiB에서 멈춤 |
| `5-r26-144m-reels-psi.jpg` | PSI(5분 평균) | r26 | 메모리 full 최고 11%로 경보 기준(빨간 점선 20%) 아래 → HighMemoryPressure 안 울림 |
| `6-r12-384m-browse-1h-latency.jpg` | 응답 시간 | r12, 힙 384MiB, 11-10 18:20~19:25 | 조회 50명 1시간. 처음 10분 뒤 p95 약 0.44초로 평평 |
| `7-r27-144m-browse-1h-latency.jpg` | 응답 시간 | r27, 힙 144MiB, 11-11 20:15~21:20 | 같은 측정. 10분 뒤 p95 약 0.44초로 r12와 같음 |

캡처 방법: `https://indigostork3365.grafana.net/d-solo/trova-essentials/x?panelId=<패널>&from=<ms>&to=<ms>&timezone=Asia/Seoul&theme=light`를 브라우저로 열어 화면 캡처(1512×806).
