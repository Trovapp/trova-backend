#!/bin/bash
# 앱 시작 시간·메모리 비교(#410). Docker(eclipse-temurin:21-jre, arm64)에서 CPU를 제한해 운영 E2.1.Micro를 흉내 낸다.
# 개발 DB 환경 파일만 쓰고(값은 출력하지 않음), 다시 보내기·Kafka는 끈다. Gemini는 부르지 않는다.
# usage: measure.sh <cpus> <runs>
set -u
S=$(cd "$(dirname "$0")" && pwd)
CPUS=${1:-0.25}; RUNS=${2:-3}
ENVF=${DEV_ENV_FILE:?개발 DB 환경 파일}
IMG=eclipse-temurin:21-jre
JOPTS="-Xmx144m -Xss256k -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC"
COMMON=(--rm --cpus "$CPUS" --memory 956m --env-file "$ENVF" -e APP_QUEUE_TYPE=async -e APP_QUEUE_REQUEUE_ENABLED=false
        -e SERVER_PORT=8080 -v "$S":/w -w /w)

prep() {
  rm -rf "$S/ext-base" "$S/ext-aot" "$S"/*.jsa
  docker run --rm -v "$S":/w -w /w $IMG java -Djarmode=tools -jar base.jar extract --destination ext-base >/dev/null
  docker run --rm -v "$S":/w -w /w $IMG java -Djarmode=tools -jar aot.jar extract --destination ext-aot >/dev/null
  # CDS 학습 실행: 컨텍스트를 띄운 직후 끝낸다(Spring 문서 방식).
  docker run "${COMMON[@]}" --cpus 2 $IMG java $JOPTS -XX:ArchiveClassesAtExit=base.jsa -Dspring.context.exit=onRefresh -jar ext-base/base.jar >"$S/train-base.log" 2>&1
  docker run "${COMMON[@]}" --cpus 2 $IMG java $JOPTS -Dspring.aot.enabled=true -XX:ArchiveClassesAtExit=aot.jsa -Dspring.context.exit=onRefresh -jar ext-aot/aot.jar >"$S/train-aot.log" 2>&1
  ls -la "$S"/*.jsa
}

run() { # name, java args...
  local name=$1; shift
  local cname="startup-$name-$$"
  local t0=$(python3 -c 'import time;print(time.time())')
  docker run -d --name "$cname" "${COMMON[@]}" -p 18080:8080 $IMG java $JOPTS "$@" >/dev/null
  local up=""
  for i in $(seq 1 1200); do
    if curl -s -m 1 localhost:18080/actuator/health 2>/dev/null | grep -q '"UP"'; then
      up=$(python3 -c "import time;print(round(time.time()-$t0,1))"); break
    fi
    sleep 0.25
  done
  local started=$(docker logs "$cname" 2>&1 | grep -o 'Started TrovaBackendApplication in [0-9.]* seconds' | grep -o '[0-9.]*' | head -1)
  sleep 20
  local rss=$(docker exec "$cname" sh -c 'grep VmRSS /proc/1/status' | awk '{print int($2/1024)}')
  docker rm -f "$cname" >/dev/null
  echo "$name cpus=$CPUS started=${started:-?}s up=${up:-timeout}s rss=${rss:-?}MiB" | tee -a "$S/results-$CPUS.txt"
}

[ -f "$S/base.jsa" ] || prep
for r in $(seq 1 "$RUNS"); do
  run base -jar base.jar
  run cds -XX:SharedArchiveFile=base.jsa -jar ext-base/base.jar
  run aot -Dspring.aot.enabled=true -jar aot.jar
  run aotcds -Dspring.aot.enabled=true -XX:SharedArchiveFile=aot.jsa -jar ext-aot/aot.jar
done
