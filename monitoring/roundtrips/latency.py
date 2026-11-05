"""DB 왕복 지연을 0 → D ms로 바꿔 같은 요청의 응답 시간 증가량을 재고, (증가량 / D)로 요청당 실제 DB 왕복 수를 추정한다.

usage: python3 monitoring/roundtrips/latency.py OUT_DIR [--delay 30] [--n 40]
격리 앱+Postgres(monitoring/capacity/compose.yml) 앞에 toxiproxy를 두고 downstream latency toxic을 건다. 끝나면 정리한다.
"""
import argparse, json, statistics, subprocess, time, urllib.request
from pathlib import Path
import count  # 같은 폴더의 토큰·시드·psql 도구 재사용

HERE = Path(__file__).resolve().parent
COMPOSE = count.COMPOSE[:-2] + ['-f', str(count.ROOT / 'monitoring/capacity/compose.yml'), '-f', str(HERE / 'latency.override.yml')]
COMPOSE[COMPOSE.index('trova-rt-332')] = 'trova-rtl-332'
count.COMPOSE = COMPOSE


def toxi(*args):
    return subprocess.run(COMPOSE + ['exec', '-T', 'toxiproxy', '/toxiproxy-cli', *args], check=True, capture_output=True, text=True).stdout


def timed(path, tok, n):
    xs = []
    for _ in range(n):
        t = time.perf_counter(); status = count.get(path, tok); xs.append((time.perf_counter() - t) * 1000)
        assert status == 200, (path, status)
    return xs


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('out'); ap.add_argument('--delay', type=int, default=30); ap.add_argument('--n', type=int, default=40)
    a = ap.parse_args()
    out = Path(a.out).resolve(); out.mkdir(parents=True, exist_ok=False)
    subprocess.run(COMPOSE + ['up', '-d'], check=True, capture_output=True)
    rows = {}
    try:
        for _ in range(150):
            try:
                with urllib.request.urlopen(count.BASE + '/actuator/health', timeout=5) as r:
                    if json.load(r).get('status') == 'UP': break
            except Exception: pass
            time.sleep(2)
        else:
            raise RuntimeError('app not UP')
        count.psql(count.SEED)
        tok = count.token()
        for path in count.ENDPOINTS:
            timed(path, tok, 10)  # 예열
        order = [0, a.delay, a.delay, 0]  # ABBA
        for i, d in enumerate(order):
            if d:
                toxi('toxic', 'add', '-t', 'latency', '-n', 'lat', '-a', f'latency={d}', 'pg')
            for path in count.ENDPOINTS:
                xs = timed(path, tok, a.n)
                rows.setdefault(path, []).append({'block': i, 'delayMs': d, 'medianMs': round(statistics.median(xs), 2),
                                                  'meanMs': round(statistics.mean(xs), 2), 'samples': [round(x, 2) for x in xs]})
            if d:
                toxi('toxic', 'remove', '-n', 'lat', 'pg')
    finally:
        subprocess.run(COMPOSE + ['down', '-v'], capture_output=True)
    summary = {}
    for path, bl in rows.items():
        base = statistics.mean(b['medianMs'] for b in bl if b['delayMs'] == 0)
        slow = statistics.mean(b['medianMs'] for b in bl if b['delayMs'] == a.delay)
        summary[path] = {'medianNoDelayMs': round(base, 2), f'median{a.delay}msDelay': round(slow, 2),
                         'estimatedRoundTrips': round((slow - base) / a.delay, 2)}
        print(f"{path:26s} 0ms={base:7.2f}  +{a.delay}ms={slow:7.2f}  roundtrips≈{(slow - base) / a.delay:.2f}")
    (out / 'latency.json').write_text(json.dumps({'delayMs': a.delay, 'n': a.n, 'order': 'ABBA', 'summary': summary, 'blocks': rows}, ensure_ascii=False, indent=1))


if __name__ == '__main__':
    main()
