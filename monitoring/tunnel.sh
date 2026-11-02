#!/usr/bin/env bash
set -euo pipefail

# 별도 터미널에서 실행. Ctrl-C로 닫는다. 이미 같은 포트가 열려 있으면 SSH가 실패한다.
# 비밀키 자체를 저장하거나 복사하지 않는다.
: "${TROVA_SSH_HOST:?TROVA_SSH_HOST=ubuntu@서버주소 를 지정하세요}"
: "${TROVA_SSH_KEY:?TROVA_SSH_KEY=비밀키경로 를 지정하세요}"
exec ssh -N -T \
  -o BatchMode=yes -o ConnectTimeout=10 \
  -o ServerAliveInterval=30 -o ServerAliveCountMax=3 \
  -o ExitOnForwardFailure=yes \
  -i "$TROVA_SSH_KEY" \
  -L 127.0.0.1:18080:127.0.0.1:8080 "$TROVA_SSH_HOST"
