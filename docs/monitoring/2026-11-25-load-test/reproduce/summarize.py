"""Build human-readable tables from saved k6 + Prometheus evidence only."""
import json,gzip,math,re
from datetime import datetime
from pathlib import Path
HERE=Path(__file__).resolve().parent;OUT=HERE.parent/'raw';ROOT=HERE.parent
rows=json.loads((OUT/'runs.json').read_text())
def read_log(p):
 return p.read_text() if p.exists() else gzip.decompress(p.with_name(p.name+'.gz').read_bytes()).decode()

def series_peak(name,start,end):
 p=OUT/('prom-'+name+'.json')
 if not p.exists():return None
 data=json.loads(p.read_text())['data']['result'];v=[float(v) for s in data for t,v in s.get('values',[]) if start<=float(t)<=end and v not in ['NaN','+Inf','-Inf']]
 return max(v) if v else None
def snapshot(row,suffix):
 p=OUT/(row['label']+'-'+suffix+'.prom.gz')
 return gzip.decompress(p.read_bytes()).decode() if p.exists() else ''
def samples(text,name,condition=None):
 out={}
 for line in text.splitlines():
  if line.startswith(name+'{') or line.startswith(name+' '):
   key,val=line.rsplit(' ',1)
   if condition is None or condition(key):out[key]=float(val)
 return out
def deltas(row,name):
 b=samples(snapshot(row,'before'),name);a=samples(snapshot(row,'after'),name)
 return {k:v-b.get(k,0) for k,v in a.items() if v-b.get(k,0)>0}
def gc_peak(start,end):
 values=[]
 for line in read_log(OUT/'gc.log').splitlines():
  if 'Pause ' not in line:continue
  stamp=re.match(r'\[([^]]+)\]',line);duration=re.search(r'([\d.]+)ms$',line)
  if stamp and duration:
   try:at=datetime.fromisoformat(stamp[1]).timestamp()
   except ValueError:continue
   if start<=at<=end:values.append(float(duration[1]))
 return max(values) if values else 0

def fmt(x,n=2):return '미제공' if x is None else f'{x:.{n}f}'
lines=['# 저장 자료 집계','', '| 경로·VU | RPS | p50/p95/p99 ms | 오류 % | 힙 최대 MiB | GC 최대 ms | DB active/pending 최대 | 외부 Places 증분 |', '|---|---:|---|---:|---:|---:|---|---:|']
for r in rows:
 if r.get('phase')!='steady' or 'recovery' in r['label']:continue
 start,end=r['start'],r['end']
 heap=series_peak('heap',start,end);gc=gc_peak(start,end)/1000;active=series_peak('db_active',start,end);pending=series_peak('db_pending',start,end)
 lines.append(f"| {r['mode']} {r['vus']}VU ({r['label']}) | {fmt(r.get('rps'))} | {' / '.join(fmt(r.get(k)) for k in ['p50_ms','p95_ms','p99_ms'])} | {fmt((r.get('errors') or 0)*100)} | {fmt(heap/1048576 if heap else None)} | {fmt(gc*1000 if gc is not None else None)} | {fmt(active,0)}/{fmt(pending,0)} | {r['after']['places']-r['before']['places']:.0f} |")
lines+=['','## HTTP 상태별 증분 (측정 전/후 스냅샷; 예열·관측 호출 포함)','']
all_deltas={}
for r in rows:
 d=deltas(r,'http_server_requests_seconds_count');all_deltas[r['label']]=d
 if r.get('phase')=='steady':
  errors={k:v for k,v in d.items() if not re.search(r'status="[23]\d\d"',k)}
  if errors:lines.append('- '+r['label']+': '+json.dumps(errors,ensure_ascii=False))
(ROOT/'stage-tables.md').write_text('\n'.join(lines)+'\n');(OUT/'http-status-deltas.json').write_text(json.dumps(all_deltas,ensure_ascii=False,indent=2))
print('\n'.join(lines[:18]))

# Slow request timestamps and exact GC pause overlaps (saved artifacts only).
pauses=[]
for line in read_log(OUT/'gc.log').splitlines():
 if not re.search(r'\[info\]\[gc\s*\]',line) or 'Pause ' not in line:continue
 stamp=re.match(r'\[([^]]+)\]',line);duration=re.search(r'([\d.]+)ms$',line)
 if stamp and duration:
  end=datetime.fromisoformat(stamp[1]).timestamp();ms=float(duration[1]);pauses.append({'start':end-ms/1000,'end':end,'ms':ms,'line':line})
slow=[]
for r in rows:
 p=OUT/(r['label']+'.log')
 if not p.exists() and not p.with_name(p.name+'.gz').exists():continue
 for line in read_log(p).splitlines():
  match=re.search(r'msg=("SLOW_SAMPLE .*?") source=console',line)
  if not match:continue
  sample=json.loads(json.loads(match[1]).removeprefix('SLOW_SAMPLE '));sample['stage']=r['label']
  overlaps=[g for g in pauses if g['start']<=sample['end']/1000 and g['end']>=sample['start']/1000]
  sample['overlapping_gc_pauses']=len(overlaps);sample['overlapping_gc_max_ms']=max((g['ms'] for g in overlaps),default=0);slow.append(sample)
(OUT/'slow-request-gc-overlap.json').write_text(json.dumps({'pauses':pauses,'slow_requests':slow},ensure_ascii=False,indent=2))
