"""Confirm limits after initialization has settled and SSE/external routes are stopped."""
import os,json,tempfile,time,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent;OUT=HERE.parent/'raw'
for _ in range(1200):
 if (OUT/'all-measurements-completed').exists():break
 time.sleep(2)
else:raise RuntimeError('earlier scenarios did not finish')
sys.path.insert(0,str(HERE));import run
with tempfile.TemporaryDirectory(prefix='trova-534-') as private:
 os.chmod(private,0o755);p=Path(private)/'tokens.json';p.write_text(json.dumps(run.tokens()));os.chmod(p,0o600)
 for mode,steps in [('detail',[10,20,40,80,160]),('memo',[1,5,10,20,40,80]),('order',[1,5,10,20,40,80])]:
  for vu in steps:
   r=run.run(mode,vu,'late-'+mode+'-'+str(vu)+'-'+str(len(run.rows)),private,'35s')
   if not r['pass']:
    time.sleep(10)
    confirm=run.run(mode,vu,'confirm-late-'+mode+'-'+str(vu)+'-'+str(len(run.rows)),private,'35s')
    if not confirm['pass']:break
  run.run('detail',1,'late-'+mode+'-recovery-'+str(len(run.rows)),private,'20s')
(OUT/'capacity-confirmation-completed').write_text(str(time.time()))
print('후기 용량 한계 재확인 완료',flush=True)
