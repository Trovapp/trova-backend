# Java AI 재시도 계측 (#294)

GeminiJsonClientImpl의ChatModel실제호출횟수와백오프실제경과시간을기존ApiCallLog attempts/waited_ms에기록한다. 호출상한3·2/4초백오프·4xx비재시도·429/5xx재시도정책은유지한다.

- monotonic clock을주입가능하게해실제대기경과시간(중단된대기의부분시간포함)을측정한다. requested sleep시간을실제시간으로위장하지않는다.
- 성공/최종실패/비재시도4xx/백오프인터럽트에서실제호출수를기록한다. 인터럽트플래그는보존한다.
- ChatModel호출에대한예외만재시도한다. 로그저장실패때이미성공한유료가능호출을다시보내지않으며기존처럼기록실패예외를호출자에게전파한다.
- FakeChatModel/Sleeper/clock으로검증하며실제외부API호출/스키마변경없음.
