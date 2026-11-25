import os,tempfile,json
from pathlib import Path
from run import run,tokens
with tempfile.TemporaryDirectory(prefix='trova-534-') as private:
 os.chmod(private,0o755);p=Path(private)/'tokens.json';p.write_text(json.dumps(tokens()));os.chmod(p,0o600)
 run('detail',5,'confirm-detail-5-idle-sse',private,'35s')
