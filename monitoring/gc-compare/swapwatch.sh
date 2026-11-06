#!/bin/sh
# 1분마다 스왑·CPU·java 메모리 한 줄 기록(Trova #331, 2026-11-05~06). 읽기만 한다. END 이후에는 아무것도 하지 않는다.
END=1793979170
[ "$(date +%s)" -gt "$END" ] && exit 0
P=$(systemctl show -p MainPID --value trova-backend)
V=$(awk "/^(pswpin|pswpout|pgmajfault) /{printf \"%s=%s \", \$1, \$2}" /proc/vmstat)
C=$(awk "/^cpu /{printf \"user=%s sys=%s steal=%s\", \$2, \$4, \$9}" /proc/stat)
M=$(awk "/^(MemAvailable|SwapFree):/{printf \"%s=%s \", \$1, \$2}" /proc/meminfo)
J=$(awk "/^(VmRSS|VmSwap):/{printf \"java_%s=%s \", \$1, \$2}" /proc/$P/status 2>/dev/null)
echo "$(TZ=Asia/Seoul date +%FT%T%z) $V$C $M$J" >> $HOME/trova-swapwatch/swapwatch.log
