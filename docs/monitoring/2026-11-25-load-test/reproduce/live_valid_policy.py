"""Correct the historical 64-member synthetic stress case; preserve it, then test valid 10-member policy."""
import json,os,subprocess,time,tempfile,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[3];OUT=HERE.parent/'raw';D='/usr/local/bin/docker'
for _ in range(1800):
 if (OUT/'external-upper-completed').exists():break
 time.sleep(2)
else:raise RuntimeError('external refinement did not finish')
sys.path.insert(0,str(HERE));import run
before_members=int(subprocess.check_output(run.C+['exec','-T','db','psql','-U','loadtest','-d','loadtest','-tA','-c','SELECT count(*) FROM trip_members WHERE trip_id=301'],text=True).strip())
for r in run.rows:
 if before_members>10 and r['mode']=='live':r['phase']='domain-stress-64-members'
run.RUNS.write_text(json.dumps(run.rows,ensure_ascii=False,indent=2))
subprocess.run(run.C+['exec','-T','db','psql','-U','loadtest','-d','loadtest','-c','DELETE FROM trip_members WHERE trip_id=301 AND user_id>10'],check=True)
(OUT/'membership-policy-correction.json').write_text(json.dumps({'at':time.time(),'before_members':before_members,'after_members':10,'source':'TripMembershipService.java:46','preserved_phase':'domain-stress-64-members','setup':'10 shared-trip viewers + 54 separate-trip viewers'},ensure_ascii=False,indent=2))
with (OUT/'live-valid-policy-generator.log').open('w') as f:
 p=subprocess.Popen([D,'run','--rm','--name','trova-loadtest-534-sse','--cpus=1','--memory=256m','--memory-swap=256m','--network=trova-loadtest-534_backend','-v',str(ROOT)+':/repo:ro','-v',str(OUT)+':/out','trova-loadtest-534-app','python3','/repo/docs/monitoring/2026-11-25-load-test/reproduce/live_viewers_valid.py'],stdout=f,stderr=subprocess.STDOUT)
 for _ in range(60):
  if (OUT/'live-valid-policy-holding').exists():break
  if p.poll() is not None:raise RuntimeError('viewer probe failed')
  time.sleep(1)
 else:raise RuntimeError('viewer admission deadline')
 with tempfile.TemporaryDirectory(prefix='trova-534-') as private:
  os.chmod(private,0o755);tf=Path(private)/'tokens.json';tf.write_text(json.dumps(run.tokens()));os.chmod(tf,0o600)
  for vu in [1,3]:run.run('live',vu,'live-valid-members10-vu'+str(vu),private,'35s')
 subprocess.run([D,'stop','-t','2','trova-loadtest-534-sse'],capture_output=True);p.wait()
(OUT/'valid-policy-completed').write_text(str(time.time()))
print('정상 정책 10명 공유 여행·전체 SSE 64개 공동 편집 검증 완료',flush=True)
