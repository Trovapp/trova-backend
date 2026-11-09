#!/usr/bin/env python3
"""운영 서버에서 영상 분석을 여러 개 넣으면서 조회 부하를 같이 주고 버티는지 본다(#344, 사용자 요청 2026-11-08).

usage:
  PROD_SSH=ubuntu@<host> PROD_SSH_KEY=<키> PROD_DB_ENV_FILE=<운영 DB 환경 파일> \\
  python3 monitoring/limit/prod_analysis.py --ids <prod_data ids 출력> --out <새 폴더> --urls <URL 파일> \\
      [--shares 10] [--browse-vus 50] [--max-minutes 25] [--offset 0]

- 테스트 계정(provider='loadtest') 사용자마다 영상 1개씩 POST /api/shares 로 넣는다(사용자별 결과 재사용을 피함).
  같은 시각에 조회 부하(load.js, --browse-vus명)를 모든 분석이 끝날 때까지 건다(--browse-vus 0이면 조회 없음).
- 5초마다 서비스 cgroup 메모리(java+파이썬), 남은 메모리, 파이썬 프로세스 수·RSS 합, steal을 기록한다.
- 끝나면 작업별 대기·처리 시간과 Gemini 등 외부 호출(api_call_logs)을 운영 DB에서 읽기 전용으로 모은다.
- 토큰은 서버 안에서 만든다(prod_run.MINT). 자격증명·토큰은 출력하지 않는다.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parent))
from prod_run import BASE, CG, HERE, MINT, ssh, wait_health  # noqa: E402

READONLY = Path(__file__).resolve().parent.parent / 'prod_readonly_psql.py'

SAMPLE = (f'cur=$(cat {CG}/memory.current); peak=$(cat {CG}/memory.peak 2>/dev/null || echo 0); '
          f'avail=$(grep MemAvailable /proc/meminfo | tr -s " " | cut -d" " -f2); '
          f'steal=$(head -1 /proc/stat | tr -s " " | cut -d" " -f9); '
          f'pid=$(systemctl show trova-backend -p MainPID --value); '
          f'jrss=$(ps -o rss= -p $pid 2>/dev/null || echo 0); '
          f'py=$(ps -eo rss=,args= | grep -E "python3? .*(pipeline-test|run_pipeline|extract_places)" | grep -v grep | '
          f'awk "{{n++; s+=\\$1}} END {{print n+0, s+0}}"); '
          f'psi=$(for r in cpu memory io; do awk "{{split(\\$5,a,\\"=\\"); printf \\"%s \\", a[2]}}" /proc/pressure/$r; done); '
          f'vm=$(awk "/^(pgmajfault|pswpin|pswpout) /{{printf \\"%s \\", \\$2}}" /proc/vmstat); '
          f'cgp=$(awk "{{split(\\$5,a,\\"=\\"); printf \\"%s \\", a[2]}}" {CG}/memory.pressure 2>/dev/null); '
          f'echo "$(date +%s) $cur $peak $avail $steal $pid $jrss $py $psi $vm $cgp"')
# 뒤쪽 값(#16 원인 가르기, 2026-11-09): PSI 누적 대기 µs — cpu some/full, memory some/full, io some/full,
# /proc/vmstat pswpin·pswpout·pgmajfault(누적), java 서비스 cgroup memory.pressure some/full. 구간 차이로 본다.
PSI_KEYS = ['cpuSomeUs', 'cpuFullUs', 'memSomeUs', 'memFullUs', 'ioSomeUs', 'ioFullUs',
            'pswpin', 'pswpout', 'pgmajfault', 'svcMemSomeUs', 'svcMemFullUs']


def readonly(sql):
    r = subprocess.run([sys.executable, str(READONLY)], input=sql, text=True, capture_output=True)
    if r.returncode != 0:
        raise RuntimeError('운영 DB 읽기 실패: ' + r.stderr.strip()[:300])
    return r.stdout


def post_share(base, token, url):
    req = urllib.request.Request(f'{base}/api/shares', data=json.dumps({'url': url}).encode(),
                                 headers={'Authorization': f'Bearer {token}', 'Content-Type': 'application/json'},
                                 method='POST')
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, {'error': e.read().decode(errors='replace')[:200]}
    except Exception as e:  # 연결 실패 등
        return None, {'error': str(e)[:200]}


def job_states(job_ids):
    if not job_ids:
        return []
    ids = ','.join(str(int(j)) for j in job_ids)
    out = readonly(f"""
select coalesce(json_agg(json_build_object('id', id, 'status', status, 'analysis', analysis_status,
  'created', extract(epoch from created_at), 'updated', extract(epoch from updated_at),
  'finished', extract(epoch from analysis_finished_at), 'error', left(error_message, 120)) order by id), '[]')
from processing_jobs where id in ({ids});""")
    return json.loads(next(line for line in out.splitlines() if line.startswith('[')))


def sampler(stop, path):
    with open(path, 'w') as f:
        while not stop.is_set():
            r = ssh(SAMPLE)
            line = r.stdout.strip().splitlines()[-1] if r.stdout.strip() else ''
            parts = line.split()
            if len(parts) >= 9:
                t, cur, peak, avail, steal, pid, jrss, pyn, pyrss = parts[:9]
                f.write(json.dumps({'t': int(t), 'serviceMiB': round(int(cur) / 2**20, 1),
                                    'servicePeakMiB': round(int(peak) / 2**20, 1),
                                    'availableMiB': round(int(avail) / 1024, 1), 'stealTicks': int(steal),
                                    'pid': pid, 'javaRssMiB': round(int(jrss) / 1024, 1),
                                    'pythonProcs': int(pyn), 'pythonRssMiB': round(int(pyrss) / 1024, 1),
                                    **{k: int(v) for k, v in zip(PSI_KEYS, parts[9:9 + len(PSI_KEYS)])}}) + '\n')
                f.flush()
            stop.wait(5)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--ids', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--urls', required=True)
    ap.add_argument('--shares', type=int, default=10)
    ap.add_argument('--browse-vus', type=int, default=50)
    ap.add_argument('--max-minutes', type=int, default=25)
    ap.add_argument('--offset', type=int, default=0, help='분석을 넣을 첫 테스트 사용자 순번(0부터)')
    ap.add_argument('--base', default=BASE)
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=False)
    urls = [u.strip() for u in Path(args.urls).read_text().splitlines() if u.strip().startswith('http')][:args.shares]
    if len(urls) < args.shares:
        print(f'URL이 {args.shares}개보다 적습니다({len(urls)}).', file=sys.stderr)
        return 1
    if not wait_health(args.base, timeout=60):
        print('앱이 UP이 아닙니다.', file=sys.stderr)
        return 1
    ids = Path(args.ids).read_text()
    with tempfile.TemporaryDirectory(prefix='trova-prod-analysis-') as private:
        token_path = Path(private) / 'tokens.json'
        script = base64.b64encode(MINT.encode()).decode()
        minted = ssh(f'sudo python3 -c "$(echo {script} | base64 -d)"', input=ids)
        if minted.returncode != 0:
            print('토큰을 만들지 못했습니다.', file=sys.stderr)
            return 1
        token_path.write_text(minted.stdout)
        os.chmod(token_path, 0o600)
        tokens = json.loads(minted.stdout)
        # 조회 부하는 분석을 넣지 않는 뒤쪽 사용자에게(같은 사용자 화면에 분석 중 폴링이 섞이지 않게)
        browse_tokens = Path(private) / 'browse.json'
        browse_tokens.write_text(json.dumps(tokens[args.offset + args.shares:] or tokens))
        os.chmod(browse_tokens, 0o600)

        stop = threading.Event()
        th = threading.Thread(target=sampler, args=(stop, out / 'samples.jsonl'), daemon=True)
        th.start()
        time.sleep(15)  # 넣기 전 기준값
        browse = None
        if args.browse_vus:
            browse_log = open(out / 'browse.log', 'w')
            browse = subprocess.Popen(
                ['k6', 'run', '--quiet', '--summary-export', str(out / 'browse.json'), str(HERE / 'load.js')],
                env={**os.environ, 'TOKENS': str(browse_tokens), 'VUS': str(args.browse_vus),
                     'DURATION': f'{args.max_minutes}m', 'THINK': '1', 'BASE': args.base},
                stdout=browse_log, stderr=subprocess.STDOUT)
            time.sleep(30)  # 조회만 있을 때 구간
        submitted = []
        t_submit = time.time()
        for i, url in enumerate(urls):
            code, body = post_share(args.base, tokens[args.offset + i]['token'], url)
            submitted.append({'user': args.offset + i + 1, 'url': url, 'status': code, 'jobId': body.get('jobId') if isinstance(body, dict) else None,
                              'alreadyAnalyzed': body.get('alreadyAnalyzed') if isinstance(body, dict) else None,
                              'error': body.get('error') if isinstance(body, dict) else None})
        (out / 'submitted.json').write_text(json.dumps(submitted, indent=2, ensure_ascii=False))
        print(json.dumps({'submitted': sum(1 for s in submitted if s['jobId']), 'of': len(urls)}), flush=True)
        job_ids = [s['jobId'] for s in submitted if s['jobId']]
        deadline = t_submit + args.max_minutes * 60
        states = []
        while time.time() < deadline:
            states = job_states(job_ids)
            pending = [s for s in states if s['status'] not in ('DONE', 'FAILED')]
            print(json.dumps({'elapsedS': round(time.time() - t_submit), 'pending': len(pending),
                              'done': sum(1 for s in states if s['status'] == 'DONE'),
                              'failed': sum(1 for s in states if s['status'] == 'FAILED')}), flush=True)
            if not pending:
                break
            time.sleep(15)
        t_done = time.time()
        time.sleep(60)  # 분석이 끝난 뒤 자동 일정 초안이 이어지는 구간도 조금 본다
        if browse:
            browse.terminate()
            try:
                browse.wait(timeout=30)
            except subprocess.TimeoutExpired:
                browse.kill()
        stop.set()
        th.join(timeout=30)
    ids_sql = ','.join(str(int(j)) for j in job_ids) or '0'
    calls = readonly(f"""
select coalesce(json_agg(json_build_object('provider', provider, 'operation', operation, 'jobId', job_id, 'success', success,
  'latencyMs', latency_ms, 'attempts', attempts, 'waitedMs', waited_ms, 'at', extract(epoch from created_at),
  'error', left(error_message, 120)) order by created_at), '[]')
from api_call_logs where created_at >= to_timestamp({int(t_submit) - 5}) and (job_id in ({ids_sql}) or job_id is null);""")
    result = {'submittedAt': t_submit, 'allDoneAt': t_done, 'drainS': round(t_done - t_submit, 1),
              'jobs': job_states(job_ids), 'submitted': submitted,
              'apiCalls': json.loads(next(line for line in calls.splitlines() if line.startswith('[')))}
    (out / 'result.json').write_text(json.dumps(result, indent=2, ensure_ascii=False))
    print(json.dumps({'drainS': result['drainS'], 'jobs': len(result['jobs']),
                      'done': sum(1 for j in result['jobs'] if j['status'] == 'DONE'),
                      'apiCalls': len(result['apiCalls'])}), flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
