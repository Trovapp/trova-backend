"""Keep app CPU/heap/memory unchanged; separate 3-second scrape disturbance from 15-second observation."""
import json,subprocess,time,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent;OUT=HERE.parent/'raw';marker=OUT/'steady-completed'
for _ in range(450):
 if marker.exists():break
 time.sleep(2)
else:raise RuntimeError('3s scrape trial did not finish')
rows=json.loads((OUT/'runs.json').read_text())
for r in rows:
 if r.get('phase')=='steady':r['phase']='scrape3s'
(OUT/'runs.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2));marker.unlink()
p=HERE/'prometheus.yml';p.write_text(p.read_text().replace('scrape_interval: 3s','scrape_interval: 15s'))
subprocess.run(['/usr/local/bin/docker','kill','--signal=HUP','trova-loadtest-534-prometheus-1'],check=True)
(OUT/'scrape-calibration.json').write_text(json.dumps({'changed_at':time.time(),'before_seconds':3,'after_seconds':15,'app_cpu':.125,'memory_mib':768,'heap_mib':144,'caveat':'same JVM sequential; warm-up and elapsed time are confounders, not a causal ABBA comparison'},indent=2))
time.sleep(30)
sys.path.insert(0,str(HERE));import run
sys.argv=['run.py','--modes','detail,memo,order'];run.main()
marker.write_text(str(time.time()))
print('15초 수집 조건 단계 측정 완료',flush=True)
