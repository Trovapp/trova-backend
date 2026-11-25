# 검증·정리와 남은 범위

- 기준 소스 `fe29e23`, 측정 JAR 지문은 `raw/environment.json`. 애플리케이션 소스·운영 설정·운영 DB 변경과 배포·머지는 없다. 보고서·재현 도구·경험 기록만 변경했다.
- `bootJar`는 측정 전 성공했다. 저장된 Compose 설정의 형식 검사와 새 Python 도구 AST/컴파일, JSON/gzip 구조 검사를 수행했다.
- 기존 운영용 고정 부하 도구의 로컬 fixture 테스트 `python3 -m unittest monitoring.test_places_load -q`: **8개 통과**. 운영에는 이 도구를 실행하지 않았다.
- 저장소 `CLAUDE.md`가 요구하는 전체 `./gradlew build` 실행: **JUnit 1,489개, 실패 0·오류 0·건너뜀 6개, BUILD SUCCESSFUL**. 부하를 모두 끝내고 Docker 정리 후 실행했다. Gradle 최대 worker 1·힙 768MiB, 테스트 JVM 힙 1GiB와 ActiveProcessorCount 2로 동시성을 제한했다. 테스트 제외 옵션은 사용하지 않았다.
- 첫 전체 빌드는 sandbox의 macOS `sysctl` 프로세스 열거 차단 때문에 Python 실행기 관련 34개가 실패했다(`ProcessHandleImpl.getProcessPids0`, `Operation not permitted (sysctl failed)`). 실패 원본·목록을 `raw/gradle-build-sandbox.log.gz`, `raw/gradle-sandbox-failures.json`에 보존했다. 승인된 프로세스 조회 권한으로 같은 소스를 다시 실행해 전체 빌드가 통과했다. 코드 변경·테스트 우회는 없다.
- 원본 로그는 종료 후 gzip으로 무손실 압축했고 원본 바이트 SHA-256을 `raw/log-archives.json`에 보관한다. 원본 k6 요약, Prometheus 전/후·시계열, cgroup, GC·스레드 덤프와 마스킹 로그, API 집계를 보관했다. 경계 시점의 snapshot 오차로 cgroup CPU 근사가 0.125를 약간 넘는 행이 있지만 Docker의 실제 CPU 한도는 0.125로 유지됐으며 각 단계 inspect 값을 저장했다.
- 감독기의 정상 종료 시 k6 컨테이너 자동 삭제와 stats 조회 사이 경합이 발견됐다. 첫 외부 단계의 `observer failed: CalledProcessError` 표시를 앱 장애로 해석하지 않는다. 감독기에서 종료 경합을 분리한 뒤 예열된 외부 경로를 다시 검사했다. 원본 표시를 지우지 않았다.
- 64명 합성 공유 여행은 제품의 멤버 상한 10명과 달랐다. 초기 결과를 `domain-stress-64-members`로 분리하고 최종 seed를 10명으로 고쳤다. 실제 10명 공유 여행·다른 여행을 합친 SSE 64개에서 작성자 1·3 VU를 재검증했다.
- 순서 변경 뒤 빈 시간 수가 달라진 후기 데이터는 1~4구간 혼합으로 표시했다. 빈 시간 4개를 고정한 최대 VU는 미확정이다. 초기 4구간 1·2 VU와 후기 혼합 16·32 VU를 같은 작업량의 단순 전후 개선으로 비교하지 않는다.
- Places 최종 **1,021회**, Gemini 실제 **35 HTTP 시도**(문장 중복 지표 108단위와 구분), 카카오 완료 1회·미확정 추가 시도 최대 1회. Gemini 텍스트 경로는 첫 429 뒤 다시 호출하지 않았다. 임베딩은 별도 모델 경로이며 이 모델의 한도 오류는 관측하지 않았다.
- Docker 프로젝트 `trova-loadtest-534`의 **컨테이너·네트워크·볼륨 0개**를 `down -v` 뒤 label 조회로 확인했다(`raw/cleanup.json`). 기존 사용자의 다른 컨테이너는 건드리지 않았다. 이미지는 남겨 두었다. 임시 합성 JWT 파일은 TemporaryDirectory 종료로 삭제했다.
- 지정 API 키 파일은 값 출력·복사·커밋 없이 Compose `env_file`로만 전달했다. 파일은 보존하며 매니저가 삭제한다. 앱 안에서 Python을 추가 실행했던 초기 카카오 진단은 OOM에 섞여, 별도 128MiB 컨테이너의 1회 성공 의존성 검사로 분리했다.
- 로컬 Grafana의 Headless Chrome 캡처는 DevTools 포트 미생성으로 실패했다. 새 화면을 캡처했다고 주장하지 않으며, 실제 Prometheus 원자료의 PNG 그래프와 이전 운영 재측정의 출처를 명시한 참고 캡처를 PDF에 사용했다.
- 차트·PDF는 비밀 파일·네트워크를 읽지 않고 저장된 자료만 렌더링한다. Python 패키지는 `/private/tmp`의 전용 venv에만 설치했고 전역 설치·도구 업데이트는 하지 않았다.

PDF와 그래프는 생성 뒤 직접 렌더링해 확인하고, Markdown 링크·비밀 패턴 검사를 수행한다. 검수 결과는 `raw/artifact-validation.json`에 남긴다. PDF가 별도의 새로운 측정 결과를 만들지는 않는다.

확인하지 않은 것은 장시간 안정성, 운영과 같은 DB RTT·클라우드 CPU 보정, 사용자 그룹별 장기 편집 충돌률, 복수 앱 인스턴스 사이 Kafka 전파, 고정 네 구간의 빈 시간 상한, 실제 운영 최대 동시 인원이다. 운영 B는 Windows 운영 측정과 충돌할 위험·추가 이득의 제한 때문에 생략했다. 영상 분석은 사용자 지시에 따라 부하에서 제외했다.
