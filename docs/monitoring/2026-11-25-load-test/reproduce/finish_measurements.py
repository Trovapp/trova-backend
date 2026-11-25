"""Sequential workload orchestration; fixed resource caps and external budgets remain in run.py."""
import json,os,subprocess,tempfile,time,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent; ROOT=HERE.parents[3];OUT=HERE.parent/'raw';D='/usr/local/bin/docker'
for _ in range(900):
 if (OUT/'steady-completed').exists():break
 time.sleep(2)
else:raise RuntimeError('steady phases did not finish')
with (OUT/'sse.log').open('w') as f:
 p=subprocess.Popen([D,'run','--rm','--name','trova-loadtest-534-sse','--cpus=1','--memory=256m','--memory-swap=256m','--network=trova-loadtest-534_backend','-v',str(ROOT)+':/repo:ro','-v',str(OUT)+':/out','trova-loadtest-534-app','python3','/repo/docs/monitoring/2026-11-25-load-test/reproduce/sse.py','--hold'],stdout=f,stderr=subprocess.STDOUT)
 for _ in range(450):
  if (OUT/'sse-holding').exists():break
  if p.poll() is not None:raise RuntimeError('SSE probe exited before live phase')
  time.sleep(2)
 else:raise RuntimeError('SSE admission deadline')
 sys.path.insert(0,str(HERE));import run
 with tempfile.TemporaryDirectory(prefix='trova-534-') as private:
  os.chmod(private,0o755);tf=Path(private)/'tokens.json';tf.write_text(json.dumps(run.tokens()));os.chmod(tf,0o600)
  for vu in [1,5,10,20,40]:
   row=run.run('live',vu,'live-'+str(vu)+'-'+str(len(run.rows)),private)
   if not row['pass']:break
  subprocess.run([D,'stop','-t','2','trova-loadtest-534-sse'],capture_output=True);p.wait();time.sleep(30)
  run.run('detail',1,'live-recovery-'+str(len(run.rows)),private,'20s')
  subprocess.run([sys.executable,str(HERE/'quota.py')],check=True)
  # Restore only synthetic external-test schedules after order experiments.
  sql="UPDATE trip_places SET visit_order=((id-1)%4)+1,visit_start_time=('09:00'::time+((id-1)%4)*interval '2 hours'),visit_end_time=('10:00'::time+((id-1)%4)*interval '2 hours') WHERE itinerary_id BETWEEN 1 AND 8"
  subprocess.run(run.C+['exec','-T','db','psql','-U','loadtest','-d','loadtest','-c',sql],check=True)
  for mode in ['alternatives','conversation','gap']:
   for vu in [1,2,4,8]:
    row=run.run(mode,vu,mode+'-'+str(vu)+'-'+str(len(run.rows)),private,'25s')
    if row['guard'] or row.get('errors',1)>=.01 or row.get('p95_ms',1e6)>10000:break
   time.sleep(20)
  # One explicit mixed workload. No video analysis path.
  run.run('mixed',5,'mixed-5-'+str(len(run.rows)),private,'35s')
(OUT/'all-measurements-completed').write_text(str(time.time()))
print('모든 격리 측정 완료',flush=True)
