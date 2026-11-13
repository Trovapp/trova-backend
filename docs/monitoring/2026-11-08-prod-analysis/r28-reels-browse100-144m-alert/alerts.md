# r28 경보 이력 (Grafana 규칙 이력 API `/api/v1/rules/history` + 탭에서 30초마다 읽은 규칙 상태, KST)

NoData↔NoData 전이는 뺐다. `*` = NoData, `~` = MissingSeries(지표가 끊겨 Normal로 돌아간 것).

| 시각 | 경보 | 전이 |
|---|---|---|
| 15:39:30 | HighHttpLatencyP95 | Normal → Pending |
| 15:40:50 | ProbeFailedExecutionsTooHigh | Normal → **Alerting** (외부 헬스 체크 실패, 측정 시작 2분 뒤) |
| 15:41:50 | ProbeFailedExecutionsTooHigh | Alerting → Normal |
| 15:42:40 | SyntheticCheckFailedExecutionsBreach | Normal* → **Alerting** |
| 15:43:50 | ProbeFailedExecutionsTooHigh | Normal → **Alerting** (16:03:50까지 유지) |
| 15:44:30 | HighHttpLatencyP95 | Pending → **Alerting** |
| 15:45:20 | TrovaApplicationErrorLogs | Normal → Pending |
| 15:45:40 | SyntheticCheckFailedExecutionsBreach | Normal* → **Alerting** (16:05:40까지) |
| 15:47:20 | TrovaApplicationErrorLogs | Pending → **Alerting** |
| 15:49:10 | HighMemoryPressure | Normal → Pending (PSI 메모리 full 5분 평균 > 20%) |
| 15:50:10 | TrovaKafkaConsumerLag | Normal → Pending |
| 15:51:10 | HighMemoryPressure | Pending → Normal (지표 끊김) |
| 15:52:10 | HighMemoryPressure | Normal → Pending |
| 15:52:10 | TrovaKafkaDeadLetter | Normal → **Alerting** (실패 토픽에 작업 도착) |
| 15:53:30 | HighHttp5xxErrorRate | Normal → Pending |
| 15:54:10 | HighMemoryPressure | Pending → Normal (지표 끊김) |
| 15:55:10 | HighMemoryPressure | Normal → Pending |
| 15:55:30 | HighHttp5xxErrorRate | Pending → **Alerting** |
| 15:57:10 | HighMemoryPressure | Pending → **Alerting** (처음 울림, Slack warning) |
| 15:59:10 | HighMemoryPressure | Alerting → Normal (지표 끊김) |
| 15:59:30 | HighHttp5xxErrorRate | Alerting → Normal |
| 16:00:10 | HighMemoryPressure | Normal → Pending |
| 16:01:30 | HighHttpLatencyP95 | Alerting → Normal |
| 16:02:10 | HighMemoryPressure | Pending → **Alerting** (두 번째) |
| 16:03:50 | ProbeFailedExecutionsTooHigh | Alerting → Normal |
| 16:04:10 | HighMemoryPressure | Alerting → Normal |
| 16:05:40 | SyntheticCheckFailedExecutionsBreach | Alerting → Normal* |
| 16:06:20 | TrovaApplicationErrorLogs | Alerting → Normal |
| 16:07:10 | TrovaKafkaDeadLetter | Alerting → Normal |

관찰:
- HighMemoryPressure는 pending(2분 유지 조건) 중에 지표가 끊겨 Normal로 되돌아가기를 세 번 반복한 뒤 15:57:10에 처음 울렸다. **서버가 멈출 정도의 메모리 압박에서는 앱이 지표를 못 보내서 "2분 유지" 조건이 자꾸 끊긴다** — 경보가 가장 필요한 순간에 늦게 울리는 구조. 외부 헬스 체크(ProbeFailed)가 16분 먼저 울렸다.
- 같은 창에 Grafana가 그린 PSI 메모리 full 5분 평균은 15:50 20.5% → 16:02 최고 28.7%, 20% 위에 15:50~16:04(14분) 머물렀다(screenshots/1-psi-5m.png).
