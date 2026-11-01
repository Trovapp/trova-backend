"""Never mints credentials. Supply an existing short-lived test-account token file."""
import argparse, subprocess, os, json, time, urllib.request, urllib.parse, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from resource_guard import stop_reason
parser = argparse.ArgumentParser(description='Bounded production GET test with resource observation. See README before running.')
parser.add_argument('--run-production', action='store_true', required=True)
parser.add_argument('--mode', choices=['health', 'places'], required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--host', required=True)
parser.add_argument('--ssh-key', required=True)
parser.add_argument('--token-file', type=Path)
args = parser.parse_args()
mode = args.mode
if mode == 'places' and args.token_file is None:
    parser.error('--token-file is required for places')
output = args.output
output.mkdir(parents=True, exist_ok=False)
env={k:v for k,v in os.environ.items() if not k.startswith('K6_')}
env.pop('READ_TOKEN', None)
env.update(TARGET='production',RUN_PRODUCTION='YES',REQUESTS='30',MODE=mode)
if mode=='places':
 env['READ_TOKEN']=args.token_file.read_text().strip()
remote="import json,os; psi=dict(x.split('=') for x in open('/proc/pressure/memory').readline().split()[1:]); m={l.split(':')[0]:int(l.split()[1]) for l in open('/proc/meminfo')}; v={l.split()[0]:int(l.split()[1]) for l in open('/proc/vmstat')}; print(json.dumps({'availableKiB':m['MemAvailable'],'swapInPages':v['pswpin'],'swapOutPages':v['pswpout'],'pageBytes':os.sysconf('SC_PAGE_SIZE'),'memorySomeAvg10':float(psi['avg10'])}))"
def sample():
 r=subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=5','-i',args.ssh_key,args.host,'python3 -'],input=remote,text=True,capture_output=True,check=True,timeout=8)
 resource=json.loads(r.stdout)
 with urllib.request.urlopen('http://127.0.0.1:9090/api/v1/query?'+urllib.parse.urlencode({'query':'up{job="trova-prod"}'}),timeout=3) as response:
  up=json.load(response)['data']['result']
 assert len(up)==1 and float(up[0]['value'][1])==1,'Production scrape is not UP'
 return {'at':time.time(),**resource}
result={'resourceGuard':'v2: 100MiB available; any swap-out; swap-in >=1MiB/s; memory PSI some avg10 >=1%; all provisional','mode':mode,'limit':{'vus':1,'requests':30,'maxSeconds':60},'resourceSamples':[sample()],'stoppedReason':None,'observer':'SSH meminfo/vmstat + local Prometheus query approximately every 5s; not included in k6 HTTP counts'}
assert result['resourceSamples'][0]['availableKiB']>=102400
assert result['resourceSamples'][0]['memorySomeAvg10']<1
result['start']=time.time()
with (output/'k6.log').open('w') as log:
 p=subprocess.Popen(['k6','run','--quiet','--summary-export',str(output/'summary.json'),'--out','json='+str(output/'points.json'),str(Path(__file__).with_name('health-smoke.js'))],env=env,stdout=log,stderr=subprocess.STDOUT)
 try:
  while p.poll() is None:
   s=sample();result['resourceSamples'].append(s)
   reason=stop_reason(result['resourceSamples'][-2],s)
   if time.time()-result['start']>75:reason='Supervisor deadline'
   if reason:
    result['stoppedReason']=reason;p.terminate();break
   try:p.wait(timeout=5)
   except subprocess.TimeoutExpired:pass
 except BaseException as e:
  result['stoppedReason']='Observer failed: '+type(e).__name__;p.terminate()
 finally:
  try:result['exitCode']=p.wait(timeout=10)
  except subprocess.TimeoutExpired:p.kill();result['exitCode']=p.wait()
result['end']=time.time()
(output/'run.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
if result['exitCode'] or result['stoppedReason']:sys.exit(1)
