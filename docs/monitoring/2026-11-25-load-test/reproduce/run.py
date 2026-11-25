"""Disposable clone only. Does not read API keys or contact production."""
import base64,hashlib,hmac,json,os,re,subprocess,tempfile,time,urllib.request,urllib.parse,gzip,threading,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent; OUT=HERE.parent/'raw'; ROOT=HERE.parents[3]
D='/usr/local/bin/docker'; C=[D,'compose','-p','trova-loadtest-534','-f',str(HERE/'compose.yml')]
APP='http://127.0.0.1:18084'; PROM='http://127.0.0.1:19094'
def cmd(args,**kw):return subprocess.check_output(args,text=True,**kw)
def get(url):
 with urllib.request.urlopen(url,timeout=15) as r:return r.read().decode()
def metrics():return get(APP+'/actuator/prometheus')
def calls(text):
 result={}
 for line in text.splitlines():
  if line.startswith('trova_api_call_seconds_count{'):
   labels,value=line.rsplit(' ',1); tags=dict(re.findall(r'(\w+)="([^"]*)"',labels))
   key=tags.get('provider','')+'/'+tags.get('operation','')+'/'+tags.get('success','')
   result[key]=result.get(key,0)+float(value)
 return result
def counts(text):
 c=calls(text);return {'places':sum(v for k,v in c.items() if k.startswith('google-places/')),'gemini_metric_units':sum(v for k,v in c.items() if k.startswith('gemini/')),'operations':c}
def db_calls():
 sql="SELECT COALESCE(json_agg(x),'[]') FROM (SELECT provider,operation,success,count(*) AS n FROM api_call_logs GROUP BY provider,operation,success) x"
 data=json.loads(cmd(C+['exec','-T','db','psql','-U','loadtest','-d','loadtest','-tA','-c',sql]))
 ops={r['provider']+'/'+r['operation']+'/'+str(r['success']).lower():r['n'] for r in data}
 return {'places':sum(v for k,v in ops.items() if k.startswith('google-places/')),'gemini_metric_units':sum(v for k,v in ops.items() if k.startswith('gemini/')),'operations':ops,'source':'isolated DB api_call_logs'}
def stats():
 return {s:cmd(C+['exec','-T',s,'cat','/sys/fs/cgroup/cpu.stat','/sys/fs/cgroup/memory.current','/sys/fs/cgroup/memory.events']) for s in ['app','db','kafka']}
def wait_ready():
 for _ in range(180):
  try:
   if json.loads(get(APP+'/actuator/health'))['status']=='UP' and json.loads(get(PROM+'/api/v1/query?query=up'))['data']['result'][0]['value'][1]=='1':return
  except Exception:pass
  time.sleep(3)
 raise RuntimeError('clone not ready')
b64=lambda x:base64.urlsafe_b64encode(x).rstrip(b'=').decode()
def tokens():
 out=[]
 for n in range(1,301):
  p=b64(b'{"alg":"HS256","typ":"JWT"}')+'.'+b64(json.dumps({'sub':str(n),'iat':int(time.time()),'exp':int(time.time())+14400}).encode())
  out.append(p+'.'+b64(hmac.new(b'isolated-loadtest-534-secret-never-production-12345678',p.encode(),hashlib.sha256).digest()))
 return out
rows=[]
RUNS=OUT/'runs.json'
if RUNS.exists():rows=json.loads(RUNS.read_text())
def run(mode,vus,label,private,duration='35s'):
 before=metrics(); used=counts(before)
 external=mode in ['alternatives','gap','conversation','mixed']
 # A request can expand to four gap searches plus tool calls; reserve >=500 calls.
 if external and used['places']>=1200:raise RuntimeError('Places budget guard before run')
 (OUT/(label+'-before.prom.gz')).write_bytes(gzip.compress(before.encode()))
 start=time.time(); resources=stats(); guard=None
 limits=cmd([D,'inspect','--format','{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}','trova-loadtest-534-app-1']).strip()
 name='trova-loadtest-534-k6'
 args=[D,'run','--rm','--name',name,'--cpus=1','--memory=512m','--memory-swap=512m','--network=trova-loadtest-534_backend','--user','0',
  '-v',str(ROOT)+':/repo:ro','-v',str(OUT)+':/out','-v',private+':/private:ro',
  '-e','MODE='+mode,'-e','VUS='+str(vus),'-e','DURATION='+duration,
  'grafana/k6:latest','run','--quiet','--summary-export','/out/'+label+'.json','/repo/docs/monitoring/2026-11-25-load-test/reproduce/load.js']
 generator=[]; timeline=[]
 with (OUT/(label+'.log')).open('w') as log:
  p=subprocess.Popen(args,stdout=log,stderr=subprocess.STDOUT)
  while p.poll() is None:
   try:
    c=db_calls() if external else used
    if external:timeline.append({'at':time.time(),**c})
    try:st=cmd([D,'stats','--no-stream','--format','{{json .}}',name],stderr=subprocess.DEVNULL)
    except subprocess.CalledProcessError:st=''  # container can exit between poll and stats; DB/log observers remain active
    if st:generator.append({'at':time.time(),'stats':st})
    # Logs are inspected in memory only: never publish raw exception URLs or keys.
    logs=cmd(C+['logs','--no-color','--since',str(start),'app'],stderr=subprocess.DEVNULL)
    if external and re.search(r'RESOURCE_EXHAUSTED|429 Too Many Requests|(?:429[^\n]{0,120}(?:quota|RESOURCE))|(?:quota[^\n]{0,100}429)',logs,re.I):guard='Gemini quota/429'
    if c['places']>=1500:guard='Places reserve stop at 1500 (<2000)'
    if time.time()-start>100:guard='supervisor deadline'
    if guard:
     subprocess.run([D,'stop','-t','2',name],capture_output=True);break
   except Exception as e:
    if external:guard='observer failed: '+type(e).__name__;subprocess.run([D,'stop','-t','2',name],capture_output=True);break
   time.sleep(1)
  rc=p.wait()
 end=time.time()
 try:after=metrics()
 except Exception:after='';guard=guard or 'app unavailable after workload'
 (OUT/(label+'-after.prom.gz')).write_bytes(gzip.compress(after.encode()))
 row={'phase':'steady','label':label,'mode':mode,'vus':vus,'start':start,'end':end,'exit':rc,'guard':guard,'app_cpu_nanos_memory_bytes':limits,'before':used,'after':counts(after),'resources_before':resources,'resources_after':(stats() if after else {}),'generator':generator}
 if (OUT/(label+'.json')).exists():
  m=json.loads((OUT/(label+'.json')).read_text())['metrics']; t=m.get('measured_latency',{})
  row.update({'p50_ms':t.get('med'),'p95_ms':t.get('p(95)'),'p99_ms':t.get('p(99)'),'errors':m.get('measured_errors',{}).get('value'),'requests':m.get('measured_requests',{}).get('count'),'rps':m.get('http_reqs',{}).get('rate')})
 row['pass']=not guard and row.get('p95_ms') is not None and row['p95_ms']<1000 and row['errors']<.01
 rows.append(row);RUNS.write_text(json.dumps(rows,ensure_ascii=False,indent=2)+'\n');(OUT/(label+'-external-timeline.json')).write_text(json.dumps(timeline,indent=2))
 print(json.dumps({k:v for k,v in row.items() if k in ['label','vus','p95_ms','rps','errors','guard','pass']},ensure_ascii=False),flush=True)
 return row

def main():
 wait_ready()
 if '--seed' in sys.argv:
  with (HERE/'seed.sql').open() as f:(OUT/'seed-explain.txt').write_text(cmd(C+['exec','-T','db','psql','-U','loadtest','-d','loadtest'],stdin=f))
 with tempfile.TemporaryDirectory(prefix='trova-534-') as private:
  os.chmod(private,0o755);f=Path(private)/'tokens.json';f.write_text(json.dumps(tokens()));os.chmod(f,0o600)
  modes=sys.argv[sys.argv.index('--modes')+1].split(',') if '--modes' in sys.argv else ['detail','memo','order']
  if '--seed' in sys.argv:run('detail',1,'warmup-60s',private,'60s')
  for mode in modes:
   ext=mode in ['alternatives','gap','conversation']
   stages=[1,2,4,8] if ext else ([5] if mode=='mixed' else [1,5,10,20,40,80,160])
   for vu in stages:
    row=run(mode,vu,mode+'-'+str(vu)+'-'+str(len(rows)),private,'25s' if ext else '35s')
    if row['guard']:break
    if not ext and not row['pass']:break
    if ext and (row.get('errors',1)>=.01 or row.get('p95_ms',1e6)>10000):break
   if not ext:run('detail',1,mode+'-recovery-'+str(len(rows)),private,'20s')
   # Quota stops this route; independent routes are assessed separately by operator.

if __name__=='__main__':main()
