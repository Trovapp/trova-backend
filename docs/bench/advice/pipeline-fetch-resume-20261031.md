# 2026-10-31 인계 후 실행 기록

- 백엔드 PR #258 squash 머지: main `6e762c2`.
- 안드로이드 production OTA 게시: 앱 main `d3005f8`, 그룹 `2986b8b4-8a01-49bd-a4fb-5e6ec725c34c`, runtime `a28468234f950d018bdc61e041544e9879e2e988`. 번들 운영 주소 검사 통과. 설치 기기 수신은 미확인.
- 운영 JAR + `pipeline-test/*.py` 배포 완료. 진행 작업 0건 확인 후 실행. JAR SHA-256 앞 16자리 `08e88164b433ad08` 일치.
- 백업: `/opt/trova-backend/app.jar.bak-20261031-125346`, `/opt/trova-backend/pipeline-test.bak-20261031-035345`(원격 UTC).
- 12:56 KST 정상화: 재시작 후 108초, 전체 배포 144초. 인증 없는 공유 401, 세션 쿠키 0개, 자동 초안 목록 401 확인. `api_call_logs.attempts`, `waited_ms` 생성 확인.
- 배포 리포트: `~/.config/flow-reports/devflow/20261031-125610-deploy.html`.
- 평가 전 Gemini 사용량: 개발 23회 + 운영 3회.
- 지정된 파이프라인 환경 파일 복사 완료. 그러나 해당 키도 401이어서 첫 before 10건은 인증 실패(정확도 결과로 사용 불가). 원본: perf 클론 `docs/eval/eval-place-extract-cases/20261031-125942.json`.
- 로컬 서버 루트 환경의 기존 키는 모델 목록 요청 200 확인. 평가용 환경의 Gemini 키만 이 값으로 갱신(값 출력 없음, 파일 권한 600) 후 before 재실행.
- 텍스트 전용 플래그 운영 활성화는 before/after 비교 전까지 보류.
- `sudo -n pmset -a disablesleep 0`은 관리자 비밀번호 필요로 실패. 사용자가 직접 실행 필요.
- 운영 실공유 검증, before/after 비교, 운영 DB를 가리키는 워크트리 환경 정책 결정, 사용자 계정 테스트 초안 정리는 아직 남음.

평가 작업 폴더: `/Users/gimtaehyeong/orca/workspaces/trova-backend/perf`.

## 유효한 before 결과

- 10개 중 3개 완전 통과, 실행 오류 0개, 평균 recall 0.7027 / precision 0.6817, 270초.
- 원본: `docs/eval/eval-place-extract-cases/20261031-130016.json`(perf 클론).
- 이어 실행한 `after`(20261031-131623)는 2초 만에 before 캐시를 재사용했으므로 **플래그 비교로 무효**.
- 플래그는 promptfoo 캐시 키에 반영되지 않는다. 환경 변수 실험은 반드시 `PROMPTFOO_CACHE_ENABLED=false`로 실행할 것.
- 실제 after는 `PROMPTFOO_CACHE_ENABLED=false PIPELINE_FILTER_TEXT_ONLY=1 ... --label after-no-cache`로 재실행.
- 캐시를 끈 after는 일부 실제 실행 후 중단했다. 코드 확인 결과 `text_only` 조건에 `not video_uri`가 있어 유튜브 링크 직접 입력 10개에는 플래그가 적용되지 않는다. 이 세트로는 B의 효과를 판단할 수 없다.
- **운영 플래그 활성화 보류.** 후속 검증은 자막/설명 + 오디오/프레임이 실제로 입력되는 샘플로 구성해야 한다. 사진 게시물도 플래그 적용 대상이 아니다.
- 운영 실공유 검증: 테스트 계정 ID 1, 작업 93, 김해 쇼츠 장소 6곳 추출 성공(22초). 이번 작업에서 생성한 장소만 API로 정리 완료. 완료 작업 기록은 유지.
