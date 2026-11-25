import json,os,subprocess,time,tempfile,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[3];OUT=HERE.parent/'raw';D='/usr/local/bin/docker'
for _ in range(1500):
 if (OUT/'capacity-confirmation-completed').exists():break
 time.sleep(2)
else:raise RuntimeError('capacity confirmation did not finish')
sys.path.insert(0,str(HERE));import run
with (OUT/'live-concurrency-generator.log').open('w') as f:
 p=subprocess.Popen([D,'run','--rm','--name','trova-loadtest-534-sse','--cpus=1','--memory=256m','--memory-swap=256m','--network=trova-loadtest-534_backend','-v',str(ROOT)+':/repo:ro','-v',str(OUT)+':/out','trova-loadtest-534-app','python3','/repo/docs/monitoring/2026-11-25-load-test/reproduce/live_viewers.py'],stdout=f,stderr=subprocess.STDOUT)
 for _ in range(60):
  if (OUT/'live-concurrency-holding').exists():break
  if p.poll() is not None:raise RuntimeError('viewer probe failed')
  time.sleep(1)
 else:raise RuntimeError('viewer admission deadline')
 with tempfile.TemporaryDirectory(prefix='trova-534-') as private:
  os.chmod(private,0o755);tf=Path(private)/'tokens.json';tf.write_text(json.dumps(run.tokens()));os.chmod(tf,0o600)
  run.run('live',3,'live-concurrent-3-final',private,'35s')
 subprocess.run([D,'stop','-t','2','trova-loadtest-534-sse'],capture_output=True);p.wait()
(OUT/'final-concurrency-completed').write_text(str(time.time()))
print('64개 SSE + 동시 수정 3 VU 완료',flush=True)
