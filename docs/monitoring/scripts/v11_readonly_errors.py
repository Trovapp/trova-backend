"""Four unauthenticated read-only error requests, eight seconds apart.

No credentials, cookies, authorization headers, or load generators are used.
Only known error fields and the public trace ID are retained.
"""
import json
import time
import urllib.request
import urllib.error
from v11_ui import stamp, log, ROOT

base = 'https://api.trova.duckdns.org'
paths = ['/api/public/trips/v11-recheck-531-no-such-trip', '/api/trip-drafts/auto']
log('host','phase-start','readonly-errors')
for index, path in enumerate(paths * 2, 1):
    start = stamp()
    log('host','request-start',f'error-{index:02d} GET {path}')
    t0 = time.perf_counter()
    try:
        response = urllib.request.urlopen(urllib.request.Request(base+path, headers={'Accept':'application/json'}), timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    raw = response.read(16384).decode('utf-8',errors='replace')
    try:
        body = json.loads(raw)
    except json.JSONDecodeError:
        body = {'format':'non-json','length':len(raw)}
    safe = {key:value for key,value in body.items() if key in {'status','message','error','code','traceId','timestamp','format','length'}}
    row = dict(sample=f'error-{index:02d}',start=start,end=stamp(),path=path,status=response.status,traceId=response.headers.get('X-Trace-Id'),body=safe,clientSeconds=round(time.perf_counter()-t0,3))
    with (ROOT/'error-observations.jsonl').open('a',encoding='utf-8') as f:
        f.write(json.dumps(row,ensure_ascii=False)+'\n')
    log('host','result',f'error-{index:02d} status={response.status} traceId={row["traceId"]}')
    print(json.dumps(row,ensure_ascii=False),flush=True)
    if response.status not in (401,404):
        raise RuntimeError('Unexpected status; no additional requests')
    time.sleep(8)
log('host','phase-end','readonly-errors')
