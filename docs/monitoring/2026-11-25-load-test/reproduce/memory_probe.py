import os,tempfile,json,time
from pathlib import Path
from run import run,tokens,OUT
with tempfile.TemporaryDirectory(prefix='trova-534-') as private:
 os.chmod(private,0o755);p=Path(private)/'tokens.json';p.write_text(json.dumps(tokens()));os.chmod(p,0o600)
 time.sleep(20)
 run('detail',1,'memory768-scrape3s-detail1',private,'35s')
(OUT/'steady-completed').write_text(str(time.time()))
