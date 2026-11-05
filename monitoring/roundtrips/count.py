"""요청 하나가 DB에 보내는 문장(왕복) 수를 격리 환경에서 센다. 운영 설정·DB를 읽지 않는다.

usage: python3 monitoring/roundtrips/count.py OUT_DIR
monitoring/capacity/compose.yml(격리 앱+Postgres)을 별도 프로젝트 이름으로 띄우고, Postgres log_statement=all로
JDBC 연결이 보낸 문장을 엔드포인트별로 모은다. 끝나면 컨테이너를 정리한다.
"""
import base64, hashlib, hmac, json, re, subprocess, sys, time, urllib.request
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ['docker', 'compose', '-p', 'trova-rt-332', '-f', str(ROOT / 'monitoring/capacity/compose.yml')]
BASE = 'http://127.0.0.1:18081'
SECRET = b'isolated-capacity-secret-307-never-use-in-production'
ENDPOINTS = ['/api/places', '/api/places/pending', '/api/trips', '/api/bookmarks', '/api/bookmarks/folders',
             '/api/notifications', '/api/auth/me', '/api/trip-drafts/auto', '/api/users/me']
REPEAT = 3
SEED = """
INSERT INTO users (id, provider, provider_user_id, nickname, created_at) VALUES (1,'google','rt-1','rt-1',now());
INSERT INTO processing_jobs (id,user_id,source_url,source_platform,status,retry_count,created_at,updated_at,analysis_status,analysis_finished_at)
SELECT j,1,'https://www.youtube.com/watch?v=rt'||j,'YOUTUBE','DONE',0,now(),now(),'DONE',now() FROM generate_series(1,4) j;
INSERT INTO saved_places (processing_job_id,user_id,place_name,region,category,latitude,longitude,source_url,source_platform,created_at)
SELECT j,1,'rt-place-'||j||'-'||p,'Seoul','cafe',37.5,127.0,'https://www.youtube.com/watch?v=rt'||j,'YOUTUBE',now()
FROM generate_series(1,4) j CROSS JOIN generate_series(1,10) p;
ANALYZE;
"""
LOG = re.compile(r'^\S+ \S+ \S+ \[(\d+)\] app=(.*?) LOG:  (?:statement|execute [^:]*): (.*)$')


def psql(sql):
    return subprocess.run(COMPOSE + ['exec', '-T', 'db', 'psql', '-q', '-v', 'ON_ERROR_STOP=1', '-U', 'capacity', '-d', 'capacity', '-c', sql],
                          check=True, capture_output=True, text=True)


def token():
    b64 = lambda v: base64.urlsafe_b64encode(v).rstrip(b'=').decode()
    head = b64(b'{"alg":"HS256","typ":"JWT"}') + '.' + b64(json.dumps({'sub': '1', 'iat': int(time.time()), 'exp': int(time.time()) + 3600}).encode())
    return head + '.' + b64(hmac.new(SECRET, head.encode(), hashlib.sha256).digest())


def get(path, tok):
    req = urllib.request.Request(BASE + path, headers={'Authorization': 'Bearer ' + tok})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


def normalize(sql):
    s = re.sub(r'\$\d+', '?', sql.strip())
    s = re.sub(r'\s+', ' ', s)
    return s[:140]


def main():
    out = Path(sys.argv[1]).resolve(); out.mkdir(parents=True, exist_ok=False)
    subprocess.run(COMPOSE + ['up', '-d'], check=True, capture_output=True)
    try:
        for _ in range(150):
            try:
                with urllib.request.urlopen(BASE + '/actuator/health', timeout=5) as r:
                    if json.load(r).get('status') == 'UP': break
            except Exception: pass
            time.sleep(2)
        else:
            raise RuntimeError('app not UP')
        psql(SEED)
        psql("ALTER SYSTEM SET log_statement='all'")  # ALTER SYSTEM은 한 문장씩(트랜잭션 블록 불가)
        psql("ALTER SYSTEM SET log_line_prefix='%m [%p] app=%a '")
        psql('SELECT pg_reload_conf()')
        time.sleep(2)
        tok = token()
        statuses = {}
        for path in ENDPOINTS:
            get(path, tok)  # 예열(첫 호출의 지연 초기화 제외)
            psql(f"SELECT 'RT_BEGIN {path}'")
            statuses[path] = [get(path, tok) for _ in range(REPEAT)]
            psql(f"SELECT 'RT_END {path}'")
        time.sleep(1)
        logs = subprocess.run(COMPOSE + ['logs', '--no-color', '--no-log-prefix', 'db'], capture_output=True, text=True).stdout
    finally:
        subprocess.run(COMPOSE + ['down', '-v'], capture_output=True)
    (out / 'db-statements.log').write_text(logs)
    result, current, stmts = {}, None, []
    for line in logs.splitlines():
        if "RT_BEGIN " in line and 'app=psql' in line:
            current, stmts = line.split("RT_BEGIN ")[1].rstrip("'"), []
            continue
        if "RT_END " in line and 'app=psql' in line and current:
            kinds = Counter(normalize(s) for s in stmts)
            result[current] = {'statuses': statuses[current], 'statementsPerRequest': round(len(stmts) / REPEAT, 2),
                               'byStatement': {k: round(v / REPEAT, 2) for k, v in kinds.most_common()}}
            current = None
            continue
        m = LOG.match(line)
        if current and m and m[2] != 'psql':
            stmts.append(m[3])
    (out / 'roundtrips.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    for path, r in result.items():
        print(f"{path:28s} status={r['statuses']} statements/request={r['statementsPerRequest']}")


if __name__ == '__main__':
    main()
