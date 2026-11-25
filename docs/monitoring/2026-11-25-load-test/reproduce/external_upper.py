"""Extend only routes that passed 32 VU; keep budget guard and never repeat quota-stopped routes."""
import os,json,tempfile,time,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent;OUT=HERE.parent/'raw'
for _ in range(1800):
 if (OUT/'external-refinement-completed').exists():break
 time.sleep(2)
else:raise RuntimeError('refinement did not finish')
sys.path.insert(0,str(HERE));import run
with tempfile.TemporaryDirectory(prefix='trova-534-') as private:
 os.chmod(private,0o755);p=Path(private)/'tokens.json';p.write_text(json.dumps(run.tokens()));os.chmod(p,0o600)
 for mode in ['alternatives','conversation','gap']:
  old=[r for r in run.rows if r['mode']==mode and r['label'].startswith('warm-external-')]
  if not old:continue
  last=old[-1]
  if last['vus']!=32 or last.get('guard') or last.get('errors',1)>=.01 or last.get('p95_ms',1e6)>=10000:continue
  # Gap can fan out to four calls; keep its in-flight reserve <=256 at 64 VU.
  for vu in ([64] if mode=='gap' else [64,128]):
   if run.db_calls()['places']>=1000:
    (OUT/('budget-before-upper-'+mode+'.json')).write_text(json.dumps(run.db_calls(),indent=2));break
   r=run.run(mode,vu,'upper-'+mode+'-'+str(vu)+'-'+str(len(run.rows)),private,'25s')
   if r.get('guard') or r.get('errors',1)>=.01 or r.get('p95_ms',1e6)>=10000:break
  time.sleep(20)
(OUT/'external-upper-completed').write_text(str(time.time()))
print('예산 내 외부 상위 단계 확인 완료',flush=True)
