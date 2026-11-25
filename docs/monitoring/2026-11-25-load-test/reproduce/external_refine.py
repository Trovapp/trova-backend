"""Retest warmed API routes; never repeat a route after an observed Gemini quota stop."""
import os,json,tempfile,time,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent;OUT=HERE.parent/'raw'
for _ in range(1800):
 if (OUT/'final-concurrency-completed').exists():break
 time.sleep(2)
else:raise RuntimeError('earlier scenarios did not finish')
sys.path.insert(0,str(HERE));import run
skip={r['mode'] for r in run.rows if r.get('guard')=='Gemini quota/429'}
with tempfile.TemporaryDirectory(prefix='trova-534-') as private:
 os.chmod(private,0o755);p=Path(private)/'tokens.json';p.write_text(json.dumps(run.tokens()));os.chmod(p,0o600)
 for mode in ['alternatives','conversation','gap']:
  if mode in skip:continue
  for vu in [1,2,4,8,16,32]:
   r=run.run(mode,vu,'warm-external-'+mode+'-'+str(vu)+'-'+str(len(run.rows)),private,'25s')
   if r['guard'] or r.get('errors',1)>=.01 or r.get('p95_ms',1e6)>10000:break
  time.sleep(20)
(OUT/'external-refinement-completed').write_text(str(time.time()))
print('예열된 외부 경로 재확인 완료',flush=True)
