"""운영 사양 복제 환경에서 동시 사용자를 늘려 한계를 찾는다(#342). 운영 설정·DB는 읽지 않는다.

usage: LIMIT_OUT=<새 결과 폴더> python3 monitoring/limit/run.py [--steps 1,5,10,20,30,40,60,80,100] [--cpus 0.125]
- 앱 컨테이너: 운영처럼 G1, 힙 384MiB, CPU 2개로 인식, 메모리 520MiB에 스왑 없음(운영 MemorySwapMax=0).
- 단계마다 같은 JVM에 VU를 늘린다. p95 1초 초과 또는 오류 1% 초과인 첫 단계에서 멈추고,
  직전·실패 단계를 한 번씩 반복한 뒤 1 VU로 회복을 본다. 끝나면 컨테이너와 DB를 지운다(--keep이면 남김).
"""
import argparse
import base64
from datetime import datetime
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.request

HERE = Path(__file__).resolve().parent
COMPOSE = HERE.parent / 'gc-compare' / 'compose.yml'
PROJECT = 'trova-limit-342'
SECRET = b'isolated-gc-secret-331-never-use-in-production'
USERS = 50
JAVA = ('-Xmx384m -Xss256k -XX:+ExitOnOutOfMemoryError -XX:ActiveProcessorCount=2 -XX:+UseG1GC '
        '-Xlog:gc*=info,gc+cpu=info:file=/gclogs/gc.log:time,uptime,level,tags')
PAUSE = re.compile(r'^\[([^\]]+)\].*\[gc +\] GC\(\d+\) (Pause .*?) \d+M->\d+M\(\d+M\) (\d+(?:\.\d+)?)ms$')
P95_LIMIT_MS, ERROR_LIMIT = 1000, 0.01


def compose(env, *args, **kw):
    return subprocess.run(['docker', 'compose', '-p', PROJECT, '-f', str(COMPOSE), *args], env=env, **kw)


def wait_health(timeout=600):  # CPU 0.125에서 기동이 5분 가까이 걸릴 때가 있다(#14)
    end = time.time() + timeout
    while time.time() < end:
        try:
            with urllib.request.urlopen('http://127.0.0.1:18082/actuator/health', timeout=5) as r:
                if json.load(r).get('status') == 'UP':
                    return
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError('앱이 UP이 되지 않았습니다')


def make_tokens():
    b64 = lambda v: base64.urlsafe_b64encode(v).rstrip(b'=').decode()
    out = []
    for user in range(1, USERS + 1):
        head = b64(b'{"alg":"HS256","typ":"JWT"}') + '.' + b64(json.dumps(
            {'sub': str(user), 'iat': int(time.time()), 'exp': int(time.time()) + 86400}).encode())
        out.append(head + '.' + b64(hmac.new(SECRET, head.encode(), hashlib.sha256).digest()))
    return out


def cgroup(env):
    out = compose(env, 'exec', '-T', 'app', 'sh', '-c',
                  'cat /sys/fs/cgroup/memory.peak /sys/fs/cgroup/memory.current; '
                  'grep -E "^(usage_usec|nr_throttled|throttled_usec)" /sys/fs/cgroup/cpu.stat',
                  capture_output=True, text=True).stdout.split()
    if len(out) < 8:
        return None
    return {'memoryPeakMiB': round(int(out[0]) / 2**20, 1), 'memoryMiB': round(int(out[1]) / 2**20, 1),
            'cpu': dict(zip(out[2::2], map(int, out[3::2])))}


def gc_pauses(path, start, end):
    xs = []
    if not path.exists():
        return {'count': 0, 'maxMs': 0, 'sumMs': 0}
    for line in path.read_text(errors='replace').splitlines():
        m = PAUSE.search(line)
        if m:
            t = datetime.strptime(m[1], '%Y-%m-%dT%H:%M:%S.%f%z').timestamp()
            if start <= t <= end:
                xs.append(float(m[3]))
    return {'count': len(xs), 'maxMs': max(xs, default=0), 'sumMs': round(sum(xs), 1)}


def run_step(env, out, token_path, label, vus, args):
    before = cgroup(env)
    start = time.time()
    summary = out / f'step-{label}.json'
    with open(out / f'step-{label}.log', 'w') as log:
        subprocess.run(['k6', 'run', '--quiet', '--summary-export', str(summary), str(HERE / 'load.js')],
                       env={**os.environ, 'TOKENS': str(token_path), 'VUS': str(vus),
                            'DURATION': args.duration, 'THINK': args.think},
                       stdout=log, stderr=subprocess.STDOUT, check=False)
    end = time.time()
    after = cgroup(env)
    state = subprocess.run(['docker', 'inspect', '-f', '{{.State.Running}} {{.State.OOMKilled}} {{.RestartCount}}',
                            f'{PROJECT}-app-1'], capture_output=True, text=True).stdout.split()
    metrics = json.loads(summary.read_text())['metrics']
    lat = metrics.get('measured_latency', {})
    per_api = {k[len('measured_latency{name:'):-1]: round(v.get('p(95)', 0), 1)
               for k, v in metrics.items() if k.startswith('measured_latency{name:')}
    measured = metrics.get('measured_requests', {}).get('count', 0)
    row = {'label': label, 'vus': vus, 'start': start, 'end': end,
           'p50Ms': round(lat.get('med', 0), 1), 'p95Ms': round(lat.get('p(95)', 0), 1),
           'p99Ms': round(lat.get('p(99)', 0), 1), 'maxMs': round(lat.get('max', 0), 1),
           'errorRate': round(metrics.get('measured_errors', {}).get('value', 1), 4),
           'measuredRequests': measured, 'rps': round(measured / max(1, float(args.duration.rstrip('s')) - 15), 1),
           'p95ByApiMs': per_api, 'gc': gc_pauses(out / 'gc' / 'gc.log', start, end),
           'appRunning': state[0] if state else None, 'oomKilled': state[1] if len(state) > 1 else None}
    if before and after:
        span = (end - start) * 1e6
        row['cpuUsedCores'] = round((after['cpu']['usage_usec'] - before['cpu']['usage_usec']) / span, 3)
        row['throttledShare'] = round((after['cpu']['throttled_usec'] - before['cpu']['throttled_usec']) / span, 3)
        row['memoryPeakMiB'] = after['memoryPeakMiB']
        row['memoryMiB'] = after['memoryMiB']
    row['pass'] = row['p95Ms'] <= P95_LIMIT_MS and row['errorRate'] <= ERROR_LIMIT and row['appRunning'] == 'true'
    print(json.dumps({k: row[k] for k in ['label', 'vus', 'p95Ms', 'p99Ms', 'errorRate', 'rps', 'pass']}
                     | {'cpu': row.get('cpuUsedCores'), 'gcMax': row['gc']['maxMs']}, ensure_ascii=False), flush=True)
    return row


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--steps', default='1,5,10,20,30,40,60,80,100')
    ap.add_argument('--cpus', default='0.125')
    ap.add_argument('--mem', default='520m')
    ap.add_argument('--duration', default='60s')
    ap.add_argument('--think', default='1')
    ap.add_argument('--threads', default='200', help='Tomcat 최대 처리 스레드(스프링 기본 200)')
    ap.add_argument('--xmx', default='384m', help='힙 최대(운영과 같은 384m이 기본)')
    ap.add_argument('--malloc-arenas', default='', help='MALLOC_ARENA_MAX(비우면 glibc 기본)')
    ap.add_argument('--java-extra', default='', help='JVM 옵션 추가(예: -XX:NativeMemoryTracking=summary)')
    ap.add_argument('--all-steps', action='store_true', help='기준을 넘어도 앱이 살아 있으면 다음 단계를 잰다')
    ap.add_argument('--keep', action='store_true', help='끝난 뒤 환경을 남긴다(시뮬레이터 확인용)')
    args = ap.parse_args()
    out = Path(os.environ.get('LIMIT_OUT', '/tmp/trova-limit')).resolve()
    out.mkdir(parents=True, exist_ok=False)
    (out / 'gc').mkdir()
    env = {**os.environ, 'APP_CPUS': args.cpus, 'APP_MEM': args.mem, 'APP_MEMSWAP': args.mem,
           'GC_LOG_DIR': str(out / 'gc'), 'GC_JAVA_OPTS': f"{JAVA.replace('-Xmx384m', '-Xmx' + args.xmx)} {args.java_extra}".strip(),
           'TOMCAT_THREADS_MAX': args.threads, 'MALLOC_ARENA_MAX': args.malloc_arenas}
    compose(env, 'down', '-v', capture_output=True)
    compose(env, 'up', '-d', check=True, capture_output=True)
    wait_health()
    with open(HERE / 'seed.sql') as sql:
        compose(env, 'exec', '-T', 'db', 'psql', '-q', '-v', 'ON_ERROR_STOP=1', '-U', 'gc', '-d', 'gc',
                stdin=sql, check=True, capture_output=True)
    meta = {'cpus': args.cpus, 'mem': args.mem, 'swap': 'none', 'java': env['GC_JAVA_OPTS'], 'duration': args.duration,
            'think': args.think, 'tomcatThreadsMax': args.threads, 'mallocArenaMax': args.malloc_arenas, 'users': USERS, 'rule': f'p95<={P95_LIMIT_MS}ms, errors<={ERROR_LIMIT}'}
    rows = []
    save = lambda: (out / 'runs.json').write_text(json.dumps({'meta': meta, 'steps': rows}, indent=2, ensure_ascii=False))
    with tempfile.TemporaryDirectory(prefix='trova-limit-342-') as private:
        token_path = Path(private) / 'tokens.json'
        token_path.write_text(json.dumps(make_tokens()))
        os.chmod(token_path, 0o600)
        rows.append(run_step(env, out, token_path, '0-warmup-1', 1, args)); save()
        steps = [int(s) for s in args.steps.split(',')]
        prev = None
        for i, vus in enumerate(steps, 1):
            row = run_step(env, out, token_path, f'{i}-vu{vus}', vus, args); rows.append(row); save()
            if row['appRunning'] != 'true':  # 앱이 죽으면(OOM 등) 반복·회복 측정은 의미가 없다
                break
            if not row['pass'] and not args.all_steps:
                if prev:
                    rows.append(run_step(env, out, token_path, f'{i}r-vu{prev}', prev, args)); save()
                rows.append(run_step(env, out, token_path, f'{i}r-vu{vus}', vus, args)); save()
                break
            prev = vus
        if rows[-1]['appRunning'] == 'true':
            rows.append(run_step(env, out, token_path, 'z-recovery-1', 1, args)); save()
    if not args.keep:
        compose(env, 'down', '-v', capture_output=True)


if __name__ == '__main__':
    main()
