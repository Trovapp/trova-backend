"""Reproduce final normal-policy protocol in a fresh disposable project and EMPTY raw directory.
Build/start Compose with APP_CPUS=1 first. API keys enter containers only via Compose env_file.
Historical 520MiB/3s and invalid 64-member stress variants are archived, not repeated here.
"""
import json,subprocess,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent
from run import C,OUT,wait_ready
if (OUT/'runs.json').exists() and json.loads((OUT/'runs.json').read_text()):
 raise RuntimeError('Preserve original raw evidence and prepare an empty raw directory in a separate checkout')
OUT.mkdir(parents=True,exist_ok=True);wait_ready()
count=subprocess.check_output(C+['exec','-T','db','psql','-U','loadtest','-d','loadtest','-tA','-c','SELECT count(*) FROM users'],text=True).strip()
if count!='0':raise RuntimeError('Clone seed requires an empty disposable DB')
with (HERE/'seed.sql').open() as seed:
 (OUT/'seed-explain.txt').write_text(subprocess.check_output(C+['exec','-T','db','psql','-U','loadtest','-d','loadtest'],stdin=seed,text=True))
for script in ['warm_and_measure.py','finish_measurements.py','late_capacity.py','live_concurrency.py','external_refine.py','external_upper.py','live_valid_policy.py','finalize_measurements.py']:
 subprocess.run([sys.executable,str(HERE/script)],check=True)
