"""Losslessly archive finished raw logs; preserve original bytes and SHA-256 in a manifest.
Run only AFTER the project and build have finished. JSON summaries remain directly readable.
"""
import gzip,hashlib,json
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent/'raw'
manifest_path=ROOT/'log-archives.json'
manifest=json.loads(manifest_path.read_text()) if manifest_path.exists() else []
files=list(ROOT.glob('*.log'))+[p for p in [ROOT/'seed-explain.txt',ROOT/'proc-memory-before-calibration.txt'] if p.exists()]
for p in sorted(files):
 data=p.read_bytes();target=p.with_name(p.name+'.gz');compressed=gzip.compress(data,mtime=0)
 target.write_bytes(compressed)
 assert gzip.decompress(target.read_bytes())==data
 manifest.append({'original_name':p.name,'archive':target.name,'original_sha256':hashlib.sha256(data).hexdigest(),'original_bytes':len(data),'archive_bytes':len(compressed)})
 p.unlink()
(ROOT/'log-archives.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print('원본 바이트 보존 gzip 로그:',len(manifest))
