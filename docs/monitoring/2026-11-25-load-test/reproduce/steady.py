"""Wait for first-use probes, then measure warmed phases without duplicate HTTP polling."""
import json,subprocess,time,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent
for _ in range(300):
 p=HERE.parent/'raw'/'runs.json'
 rows=json.loads(p.read_text()) if p.exists() else []
 if any(r['label'].startswith('order-recovery') for r in rows):break
 time.sleep(2)
else:raise RuntimeError('preflight not completed')
for row in rows:row['phase']='first-use'
p.write_text(json.dumps(rows,ensure_ascii=False,indent=2))
sys.path.insert(0,str(HERE))
from run import run,main
sys.argv=['run.py','--modes','detail,memo,order']
main()
(HERE.parent/'raw'/'steady-completed').write_text(str(time.time()))
