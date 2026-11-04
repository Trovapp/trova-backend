"""Run only against the disposable compose project; no production configuration is read."""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.request
import urllib.parse

HERE = Path(__file__).resolve().parent
OUT = Path(os.environ.get('CAPACITY_OUT', 'docs/monitoring/2026-11-02-capacity')).resolve()
OUT.mkdir(parents=True, exist_ok=True)
COMPOSE = ['docker', 'compose', '-p', 'trova-capacity-307', '-f', str(HERE / 'compose.yml')]

def get(url):
    with urllib.request.urlopen(url, timeout=10) as response:
        return json.load(response)

def command(args):
    return subprocess.check_output(args, text=True).strip()

def snapshot():
    return {service: command(COMPOSE + ['exec', '-T', service, 'cat', '/sys/fs/cgroup/cpu.stat']) for service in ['app','db']}

for _ in range(90):
    try:
        if get('http://127.0.0.1:18081/actuator/health')['status'] == 'UP': break
    except Exception: pass
    time.sleep(2)
else: raise RuntimeError('isolated app did not become ready')
with open(HERE / 'seed.sql') as sql:
    subprocess.run(COMPOSE + ['exec','-T','db','psql','-v','ON_ERROR_STOP=1','-U','capacity','-d','capacity'], stdin=sql, check=True)

b64 = lambda value: base64.urlsafe_b64encode(value).rstrip(b'=').decode()
secret = b'isolated-capacity-secret-307-never-use-in-production'
tokens = []
for user in range(1,161):
    payload = b64(b'{"alg":"HS256","typ":"JWT"}') + '.' + b64(json.dumps({'sub':str(user),'iat':int(time.time()),'exp':int(time.time())+7200}).encode())
    tokens.append(payload + '.' + b64(hmac.new(secret,payload.encode(),hashlib.sha256).digest()))

runs = []
start_all = time.time()
def run(vus, label, token_path):
    start = time.time()
    before = snapshot()
    with open(OUT / (label + '.log'), 'w') as log:
        subprocess.run(['k6','run','--quiet','--summary-export',str(OUT / (label+'.json')),str(HERE/'load.js')],
                       env={**os.environ, 'VUS':str(vus), 'TOKENS':token_path}, stdout=log, stderr=subprocess.STDOUT, check=True)
    end = time.time()
    after = snapshot()
    data = json.loads((OUT/(label+'.json')).read_text())['metrics']
    row = {'label':label,'vus':vus,'start':start,'end':end,'p95_ms':data['measured_latency']['p(95)'],
           'median_ms':data['measured_latency']['med'],'errors':data['measured_errors']['value'],
           'requests':data['measured_requests']['count'], 'cpu_before':before,'cpu_after':after}
    row['pass'] = row['p95_ms'] < 1000 and row['errors'] < .01
    runs.append(row)
    (OUT/'runs.json').write_text(json.dumps(runs,indent=2))
    print(json.dumps({k:v for k,v in row.items() if not k.startswith('cpu')}), flush=True)
    return row

with tempfile.TemporaryDirectory(prefix='trova-capacity-307-') as private:
    token_path = str(Path(private)/'tokens.json')
    Path(token_path).write_text(json.dumps(tokens)); os.chmod(token_path,0o600)
    if os.environ.get('CAPACITY_MODE') == 'index-abba':
        expected = None
        checks = []
        run(1, 'warmup-1', token_path)
        for block, indexed in [('a1',False),('b1',True),('b2',True),('a2',False)]:
            sql = ('CREATE INDEX IF NOT EXISTS idx_saved_places_user_created_id ON saved_places (user_id,created_at,id);'
                   if indexed else 'DROP INDEX IF EXISTS idx_saved_places_user_created_id;')
            subprocess.run(COMPOSE + ['exec','-T','db','psql','-v','ON_ERROR_STOP=1','-U','capacity','-d','capacity','-c',sql],check=True)
            with open(HERE/'explain.sql') as source:
                plan = subprocess.check_output(COMPOSE+['exec','-T','db','psql','-U','capacity','-d','capacity','-tA'],stdin=source,text=True)
            (OUT/(block+'-explain.json')).write_text(plan)
            # Response order/content must remain identical for every exercised user.
            hashes = []
            for user in range(40):
                req=urllib.request.Request('http://127.0.0.1:18081/api/places',headers={'Authorization':'Bearer '+tokens[user]})
                with urllib.request.urlopen(req,timeout=10) as response:
                    hashes.append(hashlib.sha256(response.read()).hexdigest())
            if expected is None: expected = hashes
            if hashes != expected: raise RuntimeError('Index changed response content/order')
            checks.append({'block':block,'identical_users':40,'hashes':hashes})
            (OUT/'response-equivalence.json').write_text(json.dumps(checks,indent=2))
            for vus in [20,40]:
                row=run(vus,block+'-'+str(vus),token_path)
                if row['errors'] > .05: raise RuntimeError('Abort: errors exceed 5%')
        run(1,'recovery-1',token_path)
    else:
        previous = 1
        for vus in [1,5,10,20,40,80,160]:
            result = run(vus,'step-'+str(vus),token_path)
            if not result['pass']:
                run(previous,'repeat-'+str(previous),token_path)
                run(vus,'repeat-'+str(vus),token_path)
                break
            previous = vus
        run(1,'recovery-1',token_path)

queries = {
 'cpu':'process_cpu_usage{job="capacity"}',
 'heap':'sum(jvm_memory_used_bytes{job="capacity",area="heap"})',
 'connections_active':'hikaricp_connections_active{job="capacity"}',
 'connections_pending':'hikaricp_connections_pending{job="capacity"}',
 'connection_acquire':'rate(hikaricp_connections_acquire_seconds_sum{job="capacity"}[20s])/rate(hikaricp_connections_acquire_seconds_count{job="capacity"}[20s])',
 'http_rate':'sum(rate(http_server_requests_seconds_count{job="capacity",uri="/api/places"}[20s]))',
 'http_mean':'sum(rate(http_server_requests_seconds_sum{job="capacity",uri="/api/places"}[20s]))/sum(rate(http_server_requests_seconds_count{job="capacity",uri="/api/places"}[20s]))',
 'up':'up{job="capacity"}',
 'gc_pause':'sum(rate(jvm_gc_pause_seconds_sum{job="capacity"}[20s]))',
}
for name, query in queries.items():
    params = urllib.parse.urlencode({'query':query,'start':start_all,'end':time.time(),'step':2})
    (OUT/('prom-'+name+'.json')).write_text(json.dumps(get('http://127.0.0.1:19090/api/v1/query_range?'+params)))
(OUT/'app.log').write_text(command(COMPOSE+['logs','--no-color','app']))
print('finished; raw evidence saved', flush=True)
