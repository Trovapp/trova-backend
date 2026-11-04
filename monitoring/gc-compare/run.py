"""Serial vs G1 on a production-like isolated app. Never reads production config.

usage: GC_OUT=/tmp/trova-gc python3 monitoring/gc-compare/run.py [--order serial,g1,g1,serial] [--duration 6m] [--sweeper 1]
Each run recreates only the app container (fresh JVM) against the same seeded DB.
"""
import argparse
import base64
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
PROJECT = 'trova-gc-331'
COMMON = ('-Xmx384m -Xss256k -XX:+ExitOnOutOfMemoryError -XX:ActiveProcessorCount=2 '
          '-Xlog:gc*=info,gc+cpu=info,safepoint=info:file=/gclogs/gc.log:time,uptime,level,tags')
VARIANTS = {'serial': '-XX:+UseSerialGC', 'g1': '-XX:+UseG1GC'}

PAUSE = re.compile(r'\[gc +\] GC\((\d+)\) (Pause .*?) \d+M->\d+M\(\d+M\) (\d+(?:\.\d+)?)ms$')
CAUSE = re.compile(r'\(([^()]*)\)\s*$')
CPU = re.compile(r'GC\((\d+)\) User=([\d.]+)s Sys=([\d.]+)s Real=([\d.]+)s')
SAFEPOINT = re.compile(r'Safepoint "([^"]+)".*Total: (\d+) ns')


def parse_gc_log(path):
    pauses, cpu = [], {}
    safepoint_total_ns, safepoint_max_ns = 0, 0
    for line in Path(path).read_text(errors='replace').splitlines():
        if (m := CPU.search(line)):
            cpu[m[1]] = {'user': float(m[2]), 'sys': float(m[3]), 'real': float(m[4])}
        elif (m := SAFEPOINT.search(line)):
            ns = int(m[2]); safepoint_total_ns += ns; safepoint_max_ns = max(safepoint_max_ns, ns)
        elif (m := PAUSE.search(line)):
            c = CAUSE.search(m[2])
            pauses.append({'id': m[1], 'kind': m[2], 'cause': c[1] if c else '', 'ms': float(m[3])})
    for p in pauses:
        p.update(cpu.get(p['id'], {}))
    return {'pauses': pauses, 'safepointTotalMs': safepoint_total_ns / 1e6, 'safepointMaxMs': safepoint_max_ns / 1e6}


def summarize(parsed):
    ps = parsed['pauses']
    def agg(sel):
        xs = [p['ms'] for p in ps if sel(p)]
        return {'count': len(xs), 'maxMs': max(xs, default=0), 'sumMs': round(sum(xs), 1)}
    return {'all': agg(lambda p: True), 'codeCache': agg(lambda p: 'CodeCache' in p['cause']),
            'full': agg(lambda p: 'Full' in p['kind']),
            'safepointTotalMs': round(parsed['safepointTotalMs'], 1), 'safepointMaxMs': round(parsed['safepointMaxMs'], 1)}


def compose(env, *args, **kw):
    return subprocess.run(['docker', 'compose', '-p', PROJECT, '-f', str(HERE / 'compose.yml'), *args], env=env, **kw)


def health(timeout=300):
    end = time.time() + timeout
    while time.time() < end:
        try:
            with urllib.request.urlopen('http://127.0.0.1:18082/actuator/health', timeout=5) as r:
                if json.load(r).get('status') == 'UP':
                    return
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError('app did not become UP')


def tokens(n=20):
    b64 = lambda v: base64.urlsafe_b64encode(v).rstrip(b'=').decode()
    secret = b'isolated-gc-secret-331-never-use-in-production'
    out = []
    for user in range(1, n + 1):
        head = b64(b'{"alg":"HS256","typ":"JWT"}') + '.' + b64(json.dumps(
            {'sub': str(user), 'iat': int(time.time()), 'exp': int(time.time()) + 86400}).encode())
        out.append(head + '.' + b64(hmac.new(secret, head.encode(), hashlib.sha256).digest()))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--order', default='serial,g1,g1,serial')
    ap.add_argument('--duration', default='6m')
    ap.add_argument('--vus', default='5')
    ap.add_argument('--sweeper', default='1', help='SweeperThreshold percent (JDK default 15)')
    ap.add_argument('--cpus', default=os.environ.get('APP_CPUS', '0.25'))
    args = ap.parse_args()
    out = Path(os.environ.get('GC_OUT', '/tmp/trova-gc')).resolve()
    out.mkdir(parents=True, exist_ok=False)
    base_env = {**os.environ, 'APP_CPUS': args.cpus}
    rows = []
    with tempfile.TemporaryDirectory(prefix='trova-gc-331-') as private:
        token_path = Path(private) / 'tokens.json'
        token_path.write_text(json.dumps(tokens())); os.chmod(token_path, 0o600)
        for i, variant in enumerate(args.order.split(',')):
            label = f'{i + 1}-{variant}'
            logdir = out / label; logdir.mkdir()
            env = {**base_env, 'GC_LOG_DIR': str(logdir),
                   'GC_JAVA_OPTS': f'{COMMON} {VARIANTS[variant]} -XX:SweeperThreshold={args.sweeper}'}
            compose(env, 'up', '-d', '--force-recreate', 'app', 'gateway', check=True, capture_output=True)
            if i == 0:
                compose(env, 'up', '-d', 'db', check=True, capture_output=True)
            health()
            if i == 0:
                with open(HERE / 'seed.sql') as sql:
                    compose(env, 'exec', '-T', 'db', 'psql', '-q', '-v', 'ON_ERROR_STOP=1', '-U', 'gc', '-d', 'gc',
                            stdin=sql, check=True, capture_output=True)
            start = time.time()
            with open(logdir / 'k6.log', 'w') as log:
                subprocess.run(['k6', 'run', '--quiet', '--summary-export', str(logdir / 'k6.json'), str(HERE / 'load.js')],
                               env={**os.environ, 'TOKENS': str(token_path), 'VUS': args.vus, 'DURATION': args.duration},
                               stdout=log, stderr=subprocess.STDOUT, check=False)
            end = time.time()
            cg = compose(env, 'exec', '-T', 'app', 'sh', '-c',
                         'cat /sys/fs/cgroup/memory.peak /sys/fs/cgroup/memory.current; grep -E "^(nr_throttled|throttled_usec|usage_usec)" /sys/fs/cgroup/cpu.stat',
                         capture_output=True, text=True).stdout.split()
            state = subprocess.run(['docker', 'inspect', '-f', '{{.State.OOMKilled}} {{.RestartCount}}',
                                    f'{PROJECT}-app-1'], capture_output=True, text=True).stdout.split()
            k6 = json.loads((logdir / 'k6.json').read_text())['metrics']
            row = {'label': label, 'variant': variant, 'start': start, 'end': end, 'cpus': args.cpus, 'sweeperThreshold': args.sweeper,
                   'k6Ms': {k: round(v, 1) for k, v in k6['measured_latency'].items() if k != 'thresholds'},
                   'requests': k6['http_reqs']['count'], 'errorRate': k6['measured_errors']['value'],
                   'memoryPeakMiB': round(int(cg[0]) / 2**20, 1), 'memoryEndMiB': round(int(cg[1]) / 2**20, 1),
                   'cpuStat': dict(zip(cg[2::2], map(int, cg[3::2]))),
                   'oomKilled': state[0] if state else None,
                   'gc': summarize(parse_gc_log(logdir / 'gc.log'))}
            rows.append(row)
            (out / 'runs.json').write_text(json.dumps(rows, indent=2))
            print(json.dumps({k: row[k] for k in ['label', 'k6Ms', 'requests', 'errorRate', 'memoryPeakMiB', 'gc']}), flush=True)


if __name__ == '__main__':
    main()
