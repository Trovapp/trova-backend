#!/usr/bin/env bash
# 오류 순간 기록(#358): 1분마다 앱 상태와 메모리 대기를 보고, 기준을 넘으면 그 순간을 파일로 남긴다.
# alloy를 평소 끈 운영(956MiB)에서 "그때 OS·JVM이 어땠나"를 잃지 않으려는 것. 기록만 하고 서비스를 건드리지 않는다.
# 기준: 헬스가 UP이 아님(5초 안) 또는 메모리 full 대기 avg10이 MEM_FULL_LIMIT% 이상. 같은 문제가 이어지면 COOLDOWN초에 한 번만.
set -u
OUT_DIR=${OUT_DIR:-/var/log/trova-incident}
APP=${APP:-http://127.0.0.1:8080}
MEM_FULL_LIMIT=${MEM_FULL_LIMIT:-10}
COOLDOWN=${COOLDOWN:-600}
KEEP=${KEEP:-60}

health=$(curl -s -m 5 "$APP/actuator/health" || true)
full10=$(awk '/^full/ { split($2, a, "="); print a[2] }' /proc/pressure/memory)
reason=""
case "$health" in *'"status":"UP"'*) ;; *) reason="health" ;; esac
if awk -v v="$full10" -v l="$MEM_FULL_LIMIT" 'BEGIN { exit !(v >= l) }'; then
    reason="${reason:+$reason+}memfull${full10}"
fi
[ -z "$reason" ] && exit 0

mkdir -p "$OUT_DIR"
last=$(cat "$OUT_DIR/.last" 2>/dev/null || echo 0)
now=$(date +%s)
[ $((now - last)) -lt "$COOLDOWN" ] && exit 0
echo "$now" > "$OUT_DIR/.last"

dir="$OUT_DIR/$(date +%Y%m%d-%H%M%S)-$reason"
mkdir -p "$dir"
{
    echo "reason=$reason health=${health:-응답없음}"
    date -Is
    uptime
    for f in cpu memory io; do echo "== pressure/$f"; cat "/proc/pressure/$f"; done
    echo "== free -m"; free -m
    echo "== vmstat 1 5"; vmstat 1 5
    echo "== 메모리 상위 15"; ps -eo pid,rss,vsz,pcpu,etimes,comm --sort=-rss | head -16
    echo "== 스왑 사용 상위"
    for p in /proc/[0-9]*; do
        s=$(awk '/^VmSwap/ { print $2 }' "$p/status" 2>/dev/null)
        [ -n "$s" ] && [ "$s" -gt 0 ] && echo "$s kB $(cat "$p/comm" 2>/dev/null)"
    done | sort -rn | head -10
} > "$dir/system.txt" 2>&1
curl -s -m 10 "$APP/actuator/prometheus" > "$dir/prometheus.txt" 2>&1
journalctl -u trova-backend --since "-5 min" --no-pager > "$dir/app.log" 2>&1
gzip -q "$dir/prometheus.txt" "$dir/app.log"

ls -1d "$OUT_DIR"/20* 2>/dev/null | head -n -"$KEEP" | xargs -r rm -rf
logger -t trova-incident "기록 $dir ($reason)"
