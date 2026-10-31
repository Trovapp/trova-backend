# main 4307b79 운영 배포 계획 검토 (#267)

상태: 로컬 빌드·배포 파일 준비 완료, **서버 전송·교체·재시작 미실행**. 사용자가 선택한 범위는 순차 머지 → main 검증 → 배포 계획 검토 → 나머지 코드 점검이다. 실제 배포는 다음 선택 사항이다. 잠자기 복구는 보류한다.

## 대상과 사전 확인

- 소스: `4307b79ace781dec36b26bff8cb5df83995a041e`(PR #262·#263·#265 포함).
- 서버: `ubuntu@193.123.161.217`, 서비스 `trova-backend`, 실행 사용자 `trova`, 작업 디렉터리 `/opt/trova-backend`.
- 로컬 작업 폴더: `/private/tmp/trova-review-267`. 루트 DB `.env`는 복사하지 않았다.
- 실제 운영 읽기 확인: active/running, `/actuator/health` UP, `APP_QUEUE_TYPE=kafka`, 분석/초안/재구성 진행 중 건수 각각 0. 이는 점검 시점의 스냅샷이며 배포 직전에 다시 확인한다.
- 현재 운영 JAR SHA-256: `08e88164b433ad08c85b2674621941bb3a4acf0a10cb15b05fe0daec446f8e84`.
- 현재 운영 extract_places.py SHA-256: `ccd0d2466259e268bef9aa11df52918a6e4516248b17f786b4e0a2efbb4628be`.
- `./gradlew build -q`: 951개 중 948 통과·실패/오류 0·opt-in 3 skip. main에서 실제 Kafka/별도 JVM 복구 테스트도 별도 통과했다(PR #268 기록).

현재 운영의 run_pipeline.py와 download.py 해시도 확인했고, 새 main에서 런타임 Python 변경은 extract_places.py 한 파일이다. 엔티티·DB 스키마·운영 설정·라이브러리 의존 버전은 이 세 PR의 제품 변경 대상이 아니다. build.gradle 추가는 opt-in 테스트 classpath 설정이다.

## 배포할 파일

| 로컬 준비 파일 | 운영 목적지 | SHA-256 |
|---|---|---|
| `build/release/4307b79/app.jar` | `/opt/trova-backend/app.jar` | `b67bd9e62149c189c0f5b76b456107bfcd46f5df5dba8d542a8594691f1a74dc` |
| `build/release/4307b79/pipeline-test/extract_places.py` | `/opt/trova-backend/pipeline-test/extract_places.py` | `0343eefb60d9474aeabd46d165613547514e14328a66e3f3c7b9ae7ab4f87d77` |

같은 폴더에 `SHA256SUMS`를 만들었다. [준비·운영 스냅샷 manifest](deployment-manifest-4307b79-20261031.json). 새 코드/재빌드 파일로 바뀌면 해시와 검증 대상을 새로 기록한다. 문서 전용 후속 커밋을 제품 소스 변경으로 해석하지 않는다.

기존 devflow 설정과 deploy.sh를 읽어 확인한 결과 기본 전송·백업·rollback은 JAR 한 파일뿐이다. 그대로 실행하면 Python 변경이 빠지고 rollback도 파일 쌍을 복원하지 못한다. 이번에는 JAR/Python 쌍을 하나의 배포 단위로 다룬다. 스크립트를 실행하거나 설정을 수정한 것은 아니다.

## 승인 후 실행 순서

1. 원격 main과 로컬 제품 소스가 대상 커밋과 맞는지, 운영 JAR/Python의 이전 해시가 위 스냅샷과 같은지 확인한다. 새 배포가 끼어 있으면 이전 파일을 덮지 말고 대상을 다시 확인한다.
2. 운영 DB의 진행 중 작업을 분석·초안뿐 아니라 **재구성까지** 읽는다. 하나라도 있으면 현재 작업이 끝난 뒤 다시 확인한다. 기존 deploy preCheck는 재구성을 세지 않으므로 보완이 필요하다.
3. 준비 파일 두 개와 SHA256SUMS만 임시 경로로 전송한다. `.env`, 키, 테스트, 평가 원본을 배포 묶음에 넣지 않는다. 서버 임시 경로에서 `sha256sum -c SHA256SUMS`로 전송을 검증한다.
4. 정지 직전 진행 작업을 다시 확인한다. 서비스 정상 종료를 기다리고, 기존 JAR와 Python을 같은 release 디렉터리의 previous에 보관한다. 두 파일을 소유자 trova·모드 644로 교체하고 각각 해시를 확인한 뒤 서비스를 시작한다. 실행 중에 Python만 먼저 갈아 끼우지 않는다.
5. 최대 240초 동안 헬스 UP과 서비스 active를 확인한다. 새 프로세스가 Kafka 설정을 유지하고 JAR/Python 해시가 준비 파일과 일치하는지 확인한다. 진단 플래그 TRACE/AUDIT와 TEXT_ONLY를 새로 활성화하지 않는다.
6. 인증 없는 공유/자동 초안 조회가 401이고 세션 쿠키가 생기지 않는지 확인한다. 실제 영상 분석 smoke는 호출 한도를 확인한 뒤 별도 기록하며, 이번 계획 검토에서 실행하지 않았다.
7. 전송·교체·시작·헬스/인증 검사 중 문제가 나면 실패 지점을 기록하고 아래 파일 쌍을 복원한다. 기본 deploy.sh는 자동 rollback을 수행하지 않는다는 점을 실행 시 반영한다.

읽기 전용 진행 작업 확인 명령:

```bash
/Users/gimtaehyeong/.config/devflow/trova/venv/bin/python \
  /Users/gimtaehyeong/.config/devflow/trova/dbq.py \
  /Users/gimtaehyeong/orca/workspaces/trova-backend/job-queue/.env \
  "select (select count(*) from processing_jobs where status in ('PENDING','PROCESSING')) as analyses, (select count(*) from trip_drafts where status in ('PENDING','PROCESSING')) as drafts, (select count(*) from trip_replan_jobs where status in ('PENDING','PROCESSING')) as replans"
```

서비스 중지와 파일 교체의 검토용 명령(실행 전 payload 전송/해시 검증과 진행 작업 0 확인이 선행돼야 함):

```bash
# 원격에서 sudo 권한으로 실행할 명령. 이번 검토에서는 실행하지 않았다.
set -euo pipefail
trova_release=/opt/trova-backend/releases/main-4307b79-20261031
trova_payload=/tmp/trova-release-4307b79
test ! -e "$trova_release/previous"
cd "$trova_payload"
sha256sum -c SHA256SUMS
systemctl stop trova-backend
mkdir -p "$trova_release/previous"
cp -p /opt/trova-backend/app.jar "$trova_release/previous/app.jar"
cp -p /opt/trova-backend/pipeline-test/extract_places.py "$trova_release/previous/extract_places.py"
install -o trova -g trova -m 644 app.jar /opt/trova-backend/app.jar
install -o trova -g trova -m 644 pipeline-test/extract_places.py /opt/trova-backend/pipeline-test/extract_places.py
cd /opt/trova-backend
sha256sum -c "$trova_payload/SHA256SUMS"
systemctl start trova-backend
```

これは単独で障害時復元まで行う自動化スクリプトではない。実行担当は停止後の各失敗を検知し、バックアップが揃う前なら既存ファイルのまま起動、揃った後の交替失敗なら以下を実行する。サービス停止前の0件確認と新規リクエスト到着の間を完全に閉じる受付制御は現構成にない。Kafkaの再配信を全ジョブ種別の保証とみなさない。

## 復元するファイル対

旧ファイルの所在:

- `/opt/trova-backend/releases/main-4307b79-20261031/previous/app.jar`
- `/opt/trova-backend/releases/main-4307b79-20261031/previous/extract_places.py`

```bash
# 原状復帰が必要になった場合だけ、原本2ファイルの存在を先に確認する。
set -euo pipefail
trova_previous=/opt/trova-backend/releases/main-4307b79-20261031/previous
test -f "$trova_previous/app.jar"
test -f "$trova_previous/extract_places.py"
systemctl stop trova-backend
install -o trova -g trova -m 644 "$trova_previous/app.jar" /opt/trova-backend/app.jar
install -o trova -g trova -m 644 "$trova_previous/extract_places.py" /opt/trova-backend/pipeline-test/extract_places.py
systemctl start trova-backend
```

복원 후에도 이전 해시·UP·인증 경계·Kafka 설정을 확인한다. 이번에는 새 스키마 변경이나 환경 변수 변경이 없으므로 DB나 .env를 되돌리는 작업은 포함하지 않는다.

## 남은 판단

추가 리뷰에서 재구성 호출 한도와 관리자 감사 기록의 우선 수정 사항을 확인했다. [코드 리뷰](extended-code-review-20261031.md). 기존 코드의 문제이며 이번 세 PR의 새 회귀는 아니지만, 다음에는 이 두 건을 먼저 수정하는 안을 권장한다. 현재 main의 개선을 먼저 배포하더라도 이번 리뷰 사항이 수정됐다고 취급하지 않는다. 실제 배포와 실제 API smoke는 사용자 선택 후 진행한다.
