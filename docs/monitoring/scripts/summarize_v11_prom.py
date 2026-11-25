"""Offline counter differences; reset checks, status-separated HTTP and SQL scopes."""
import gzip
import json
import math
import re
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / '2026-11-25-v11-recheck'

def read(path):
    opener = gzip.open if path.suffix == '.gz' else open
    result = {}
    with opener(path, 'rt', encoding='utf-8') as f:
        for line in f:
            match = re.match(r'^([\w:]+)(?:\{(.*?)\})?\s+([^\s]+)', line)
            if not match:
                continue
            name, raw, value = match.groups()
            labels = tuple(sorted((k, json.loads('"'+v+'"')) for k,v in re.findall(r'(\w+)="((?:\\.|[^"\\])*)"', raw or '')))
            result[name,labels] = float(value)
    return result

def quantile(buckets, count, q=.95):
    if not count or not buckets:
        return None
    rank = count*q
    lower, previous = 0.0, 0.0
    for upper, cumulative in sorted(buckets.items()):
        if cumulative >= rank:
            # A +Inf-only tail has no finite upper bound; do not call it 30 s.
            estimate = None if math.isinf(upper) else lower+(rank-previous)/(cumulative-previous)*(upper-lower)
            return dict(estimate=estimate,lower=lower,upper=None if math.isinf(upper) else upper,rank=rank)
        lower, previous = upper, cumulative
    raise ValueError('Histogram count does not cover rank')

def summarize(before, after):
    start=lambda data:[v for (n,l),v in data.items() if n=='process_start_time_seconds']
    assert start(before)==start(after) and len(start(before))==1, 'Process changed or start missing'
    delta={}
    new=[]
    for key,value in after.items():
        name,labels=key
        if name.startswith(('http_server_requests_seconds_', 'trova_db_queries_per_request_', 'trova_api_call_seconds_', 'trova_analysis_execution_seconds_', 'trova_analysis_stage_seconds_', 'trova_pipeline_stage_seconds_', 'trova_trip_events_')) and name.endswith(('_count','_sum','_bucket','_total')):
            difference=value-before.get(key,0)
            assert difference>=-1e-7, f'Counter reset: {name} {labels}'
            delta[key]=max(0,difference)
            if key not in before and value: new.append(dict(name=name,labels=dict(labels)))
    http=defaultdict(lambda:dict(count=0,sumSeconds=0,buckets=defaultdict(float)))
    sql=defaultdict(lambda:dict(count=0,sum=0))
    timers=defaultdict(lambda:dict(count=0,sumSeconds=0))
    for (name,pairs),value in delta.items():
        labels=dict(pairs)
        if name.startswith('http_server_requests_seconds_'):
            key=tuple(labels.get(k,'') for k in ('method','uri','status'))
            row=http[key]
            if name.endswith('_count'):row['count']+=value
            elif name.endswith('_sum'):row['sumSeconds']+=value
            elif name.endswith('_bucket'):row['buckets'][float(labels['le'])]+=value
        elif name in ('trova_db_queries_per_request_count','trova_db_queries_per_request_sum'):
            key=tuple(labels.get(k,'') for k in ('method','uri'))
            sql[key]['count' if name.endswith('_count') else 'sum']+=value
        elif name.startswith(('trova_api_call_seconds_','trova_analysis_execution_seconds_','trova_analysis_stage_seconds_','trova_pipeline_stage_seconds_')) and name.endswith(('_count','_sum')):
            key=(name.rsplit('_',1)[0],tuple((k,v) for k,v in pairs if k!='application'))
            timers[key]['count' if name.endswith('_count') else 'sumSeconds']+=value
    h=[]
    for (method,uri,status),row in sorted(http.items()):
        if not row['count']:continue
        buckets=row.pop('buckets')
        row.update(method=method,uri=uri,status=status,meanSeconds=row['sumSeconds']/row['count'],p95=quantile(buckets,row['count']),buckets=[dict(le=None if math.isinf(k) else k,count=v) for k,v in sorted(buckets.items())])
        h.append(row)
    return dict(processStart=start(before)[0],newSeriesAssumedZero=new,http=h,
                sql=[dict(method=k[0],uri=k[1],**v,perRequest=v['sum']/v['count']) for k,v in sorted(sql.items()) if v['count']],
                timers=[dict(metric=k[0],labels=dict(k[1]),**v,meanSeconds=v['sumSeconds']/v['count']) for k,v in sorted(timers.items()) if v['count']],
                eventCounters=[dict(metric=n,labels=dict(l),delta=v) for (n,l),v in delta.items() if n.startswith('trova_trip_events_')],
                gauges=[dict(metric=n,labels=dict(l),before=before.get((n,l)),after=v) for (n,l),v in after.items() if n in ('trova_trip_events_deferred_size','kafka_consumer_fetch_manager_records_lag')])

def main():
    metadata=json.loads((ROOT/'server/snapshots.json').read_text(encoding='utf-8'))
    result=[]
    for window in metadata:
        result.append(dict(**window,**summarize(read(ROOT/'server'/window['beforeFile']),read(ROOT/'server'/window['afterFile']))))
    (ROOT/'server-deltas.json').write_text(json.dumps(result,ensure_ascii=False,indent=2,allow_nan=False),encoding='utf-8')
    for row in result:
        print(row['route'])
        for h in row['http']:
            if any(x in h['uri'] for x in ('alternatives','messages','gap-recommendations','details','order','events')) or h['uri']=='/api/trips/{id}':
                print(h['method'],h['uri'],h['status'],h['count'],round(h['meanSeconds'],4),h['p95'])

if __name__=='__main__':
    main()
