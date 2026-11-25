"""Boot with 1 CPU (configured container memory / 144MiB heap), warm paths, then enforce 0.125 CPU for ALL measured loads."""
import json,os,subprocess,tempfile,time,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE));import run
run.wait_ready()
with tempfile.TemporaryDirectory(prefix='trova-534-') as private:
 os.chmod(private,0o755);p=Path(private)/'tokens.json';p.write_text(json.dumps(run.tokens()));os.chmod(p,0o600)
 for mode in ['detail','memo','order']:
  run.run(mode,1,'warm-'+mode+'-cpu1',private,'12s')
for row in run.rows:row['phase']='warmup-cpu1'
run.RUNS.write_text(json.dumps(run.rows,ensure_ascii=False,indent=2))
subprocess.run([run.D,'update','--cpus=0.125','trova-loadtest-534-app-1'],check=True)
time.sleep(20)
sys.argv=['run.py','--modes','detail,memo,order'];run.main()
(run.OUT/'steady-completed').write_text(str(time.time()))
print('예열 후 CPU 0.125 단계 측정 완료',flush=True)
