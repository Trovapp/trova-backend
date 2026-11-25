"""Host observer of the bounded SSE generator only; no application traffic."""
import json,subprocess,time
from pathlib import Path
OUT=Path(__file__).resolve().parent.parent/'raw';rows=[]
for _ in range(360):
 if (OUT/'all-measurements-completed').exists():break
 p=subprocess.run(['/usr/local/bin/docker','stats','--no-stream','--format','{{json .}}','trova-loadtest-534-sse'],text=True,capture_output=True)
 if p.returncode==0 and p.stdout.strip():
  rows.append({'at':time.time(),'stats':json.loads(p.stdout)})
  (OUT/'sse-generator-samples.json').write_text(json.dumps(rows,indent=2))
 time.sleep(5)
