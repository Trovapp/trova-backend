import json,pathlib,statistics,math,sys
p=pathlib.Path(sys.argv[1])
runs=json.loads((p/'runs.json').read_text())
rows=[]
for r in runs:
 d=json.loads((p/(r['label']+'.json')).read_text())['metrics']
 cpus=[]
 for service in ['app','db']:
  b=dict(s.split() for s in r['cpu_before'][service].splitlines());a=dict(s.split() for s in r['cpu_after'][service].splitlines())
  cpus.append((int(a['usage_usec'])-int(b['usage_usec']))/1e6/(r['end']-r['start'])*100)
 vals={}
 for metric in ['connections_pending','connections_active','connection_acquire','http_rate','up']:
  arr=json.loads((p/('prom-'+metric+'.json')).read_text())['data']['result']
  samples=[float(v) for series in arr for t,v in series['values'] if r['start']+15<=float(t)<=r['end'] and math.isfinite(float(v))]
  vals[metric]={'max':max(samples),'mean':statistics.mean(samples)} if samples else None
 rows.append({'label':r['label'],'vus':r['vus'],'p95_ms':r['p95_ms'],'median_ms':r['median_ms'],'errors':r['errors'],'measured_requests':r['requests'],'all_requests':d['http_reqs']['count'],'whole_run_rps':d['http_reqs']['rate'],'app_cpu_percent':cpus[0],'db_cpu_percent':cpus[1],'prometheus':vals})
(p/'summary.json').write_text(json.dumps(rows,indent=2))
for r in rows: print(r['label'],round(r['p95_ms']),round(r['whole_run_rps'],2),round(r['db_cpu_percent'],1),r['prometheus']['connections_pending'])
