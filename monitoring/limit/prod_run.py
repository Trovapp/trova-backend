#!/usr/bin/env python3
"""운영 서버에 동시 사용자를 늘려 가며 부하를 준다(#342, 사용자 승인 2026-11-08: "운영서버에 실제 부하를 줘서 봐줘").

usage: PROD_SSH=ubuntu@<host> PROD_SSH_KEY=<키> python3 monitoring/limit/prod_run.py --ids <prod_data ids 출력> --out <새 폴더> [--steps 5,10,20,30,40,60,80,100]
- 조회 API만 부른다(영상 분석·Gemini 없음). 테스트 계정 토큰은 서버 안에서 JWT_SECRET으로 만들고, 비밀 값은 서버 밖으로 나오지 않는다.
- 먼저 --warmup-vus명으로 --warmup초 예열한다(측정에서 뺀다). 단계마다 복제 환경과 같은 지표에 더해
  서비스 cgroup 메모리·CPU, 남은 메모리, java 재시작 여부, 그 구간 GC 멈춤을 기록한다.
- java가 죽거나(PID가 바뀌거나) 오류가 20%를 넘으면 그 단계에서 멈추고, 앱이 다시 UP이 될 때까지 기다린다.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parent))
from run import P95_LIMIT_MS, ERROR_LIMIT, gc_pauses  # noqa: E402

HERE = Path(__file__).resolve().parent
BASE = 'https://193-123-161-217.sslip.io'
CG = '/sys/fs/cgroup/system.slice/trova-backend.service'
STOP_ERROR = 0.2

MINT = r'''
import base64, hashlib, hmac, json, sys, time
secret = None
for line in open('/opt/trova-backend/.env', encoding='utf-8'):
    if line.startswith('JWT_SECRET='):
        secret = line.split('=', 1)[1].strip().strip('"').strip("'")
b64 = lambda v: base64.urlsafe_b64encode(v).rstrip(b'=').decode()
now = int(time.time())
out = []
for row in json.load(sys.stdin):
    head = b64(b'{"alg":"HS256","typ":"JWT"}') + '.' + b64(json.dumps({'sub': str(row['userId']), 'iat': now, 'exp': now + 4 * 3600}).encode())
    out.append({'token': head + '.' + b64(hmac.new(secret.encode(), head.encode(), hashlib.sha256).digest()), 'tripId': row['tripId']})
print(json.dumps(out))
'''

STATE = (f'pid=$(systemctl show trova-backend -p MainPID --value); act=$(systemctl is-active trova-backend); '
         f'nr=$(systemctl show trova-backend -p NRestarts --value); '
         f'cur=$(cat {CG}/memory.current); peak=$(cat {CG}/memory.peak 2>/dev/null || echo 0); '
         f'cpu=$(grep usage_usec {CG}/cpu.stat | cut -d" " -f2); '
         f'avail=$(grep MemAvailable /proc/meminfo | tr -s " " | cut -d" " -f2); '
         f'steal=$(head -1 /proc/stat | tr -s " " | cut -d" " -f9); '
         f'echo "{{\\"pid\\":\\"$pid\\",\\"active\\":\\"$act\\",\\"restarts\\":\\"$nr\\",\\"mem\\":$cur,\\"peak\\":$peak,\\"cpu\\":$cpu,\\"availKiB\\":$avail,\\"stealTicks\\":$steal}}"')


def ssh(cmd, **kw):
    return subprocess.run(['ssh', '-i', os.path.expanduser(os.environ['PROD_SSH_KEY']), '-o', 'ConnectTimeout=15',
                           os.environ['PROD_SSH'], cmd], capture_output=True, text=True, **kw)


def state():
    r = ssh(STATE)
    try:
        return json.loads(r.stdout.strip().splitlines()[-1])
    except (IndexError, json.JSONDecodeError):
        return None


def wait_health(base, timeout=900):
    end = time.time() + timeout
    while time.time() < end:
        try:
            with urllib.request.urlopen(f'{base}/actuator/health', timeout=10) as r:
                if json.load(r).get('status') == 'UP':
                    return True
        except Exception:
            pass
        time.sleep(5)
    return False


def k6(token_path, vus, duration, base, summary, log):
    subprocess.run(['k6', 'run', '--quiet', '--summary-export', str(summary), str(HERE / 'load.js')],
                   env={**os.environ, 'TOKENS': str(token_path), 'VUS': str(vus), 'DURATION': duration,
                        'THINK': '1', 'BASE': base},
                   stdout=log, stderr=subprocess.STDOUT, check=False)


def step(out, token_path, label, vus, args):
    before = state()
    start = time.time()
    summary = out / f'step-{label}.json'
    with open(out / f'step-{label}.log', 'w') as log:
        k6(token_path, vus, args.duration, args.base, summary, log)
    end = time.time()
    after = state()
    metrics = json.loads(summary.read_text())['metrics']
    lat = metrics.get('measured_latency', {})
    measured = metrics.get('measured_requests', {}).get('count', 0)
    row = {'label': label, 'vus': vus, 'start': start, 'end': end,
           'p50Ms': round(lat.get('med', 0), 1), 'p95Ms': round(lat.get('p(95)', 0), 1),
           'p99Ms': round(lat.get('p(99)', 0), 1), 'maxMs': round(lat.get('max', 0), 1),
           'errorRate': round(metrics.get('measured_errors', {}).get('value', 1), 4),
           'measuredRequests': measured, 'rps': round(measured / max(1, float(args.duration.rstrip('s')) - 15), 1),
           'p95ByApiMs': {k[len('measured_latency{name:'):-1]: round(v.get('p(95)', 0), 1)
                          for k, v in metrics.items() if k.startswith('measured_latency{name:')},
           'before': before, 'after': after}
    died = not before or not after or after['pid'] != before['pid'] or after['active'] != 'active'
    row['appSurvived'] = not died
    if before and after and not died:
        row['cpuUsedCores'] = round((after['cpu'] - before['cpu']) / ((end - start) * 1e6), 3)
        row['memoryMiB'] = round(after['mem'] / 2**20, 1)
        row['memoryPeakMiB'] = round(after['peak'] / 2**20, 1)
        row['availableMiB'] = round(after['availKiB'] / 1024, 1)
        # 하이퍼바이저가 가져간 CPU 시간(100틱=1초). 버스트가 끝나면 커진다.
        row['stealCores'] = round((after['stealTicks'] - before['stealTicks']) / 100 / (end - start), 3)
    gc_local = out / 'gc-window.log'
    gc_local.write_text(ssh('sudo tail -n 20000 /opt/trova-backend/gc-logs/gc.log').stdout)
    row['gc'] = gc_pauses(gc_local, start, end)
    row['pass'] = row['p95Ms'] <= P95_LIMIT_MS and row['errorRate'] <= ERROR_LIMIT and not died
    print(json.dumps({k: row.get(k) for k in ['label', 'vus', 'p95Ms', 'errorRate', 'rps', 'appSurvived',
                                               'memoryMiB', 'availableMiB', 'cpuUsedCores', 'stealCores']}
                     | {'gcMax': row['gc']['maxMs']}, ensure_ascii=False), flush=True)
    return row


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--ids', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--steps', default='5,10,20,30,40,60,80,100')
    ap.add_argument('--duration', default='60s')
    ap.add_argument('--warmup-vus', type=int, default=10)
    ap.add_argument('--warmup', default='180s')
    ap.add_argument('--base', default=BASE)
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=False)
    if not wait_health(args.base, timeout=60):
        print('앱이 UP이 아닙니다. 멈춥니다.', file=sys.stderr)
        return 1
    ids = Path(args.ids).read_text()
    rows = []
    save = lambda: (out / 'runs.json').write_text(json.dumps(
        {'meta': {'base': args.base, 'duration': args.duration, 'warmup': [args.warmup_vus, args.warmup],
                  'rule': f'p95<={P95_LIMIT_MS}ms, errors<={ERROR_LIMIT}', 'stop': f'java 재시작 또는 오류>{STOP_ERROR}'},
         'steps': rows}, indent=2, ensure_ascii=False))
    with tempfile.TemporaryDirectory(prefix='trova-prod-limit-') as private:
        token_path = Path(private) / 'tokens.json'
        script = base64.b64encode(MINT.encode()).decode()
        minted = ssh(f'sudo python3 -c "$(echo {script} | base64 -d)"', input=ids)
        if minted.returncode != 0:
            print('토큰을 만들지 못했습니다.', file=sys.stderr)
            return 1
        token_path.write_text(minted.stdout)
        os.chmod(token_path, 0o600)
        warm_args = argparse.Namespace(**{**vars(args), 'duration': args.warmup})
        rows.append(step(out, token_path, f'0-warmup-{args.warmup_vus}', args.warmup_vus, warm_args)); save()
        for i, vus in enumerate([int(s) for s in args.steps.split(',')], 1):
            row = step(out, token_path, f'{i}-vu{vus}', vus, args)
            rows.append(row); save()
            if not row['appSurvived'] or row['errorRate'] > STOP_ERROR:
                if not row['appSurvived']:
                    t = time.time()
                    up = wait_health(args.base)
                    row['recoveredAfterS'] = round(time.time() - t, 1) if up else None
                    save()
                break
        rows.append(step(out, token_path, 'z-recovery-1', 1, args)); save()
    return 0


if __name__ == '__main__':
    sys.exit(main())
