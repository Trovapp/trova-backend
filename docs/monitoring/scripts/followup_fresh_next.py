"""Advance ONE fresh-place trial with OCR gates; never retry a lookup.

Start only on the previous verified alternative result in V11-FRESH-533.
An OCR/state mismatch stops before the next dependent action.
"""
import json
import subprocess
import sys
from pathlib import Path
from followup_ui import ROOT,SCRATCH,adb,log
sys.stdout.reconfigure(encoding='utf-8')
n=int(sys.argv[1]); query=sys.argv[2]; expected=sys.argv[3]
assert 5<=n<=30
assert not (ROOT/'fresh'/f'{n:02d}').exists()
scripts=Path(__file__).resolve().parent
def py(name,*args):
    subprocess.run([sys.executable,str(scripts/name),*args],check=True)
def ps(name,*args):
    return subprocess.check_output(['powershell','-NoProfile','-Command',
        '[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; & $args[0] @($args | Select-Object -Skip 1)',str(scripts/name),*args])
def ocr(path):
    command="[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; & '"+str(scripts/'followup_ocr.ps1')+"' -AllText -ImagePath '"+str(path)+"'"
    data=subprocess.check_output(['powershell','-NoProfile','-Command',command])
    rows=json.loads(data.decode('utf-8-sig'))
    return rows,' '.join(x['text'] for x in rows)
def snap():
    path=SCRATCH/'5586.png';path.write_bytes(adb('5586','exec-out','screencap','-p'));return path
def normalized(s):return ''.join(s.split())
_,current=ocr(snap())
assert '대안 찾기' in current and '대신' in current, 'Not on prior result; no action taken'
py('followup_fresh_clear.py')
_,empty=ocr(snap())
assert '아직 장소가 없어요' in empty,'Trip not empty; stopped'
command="& '"+str(scripts/'followup_fresh_search.ps1')+"' -Query '"+query.replace("'","''")+"'"
subprocess.run(['powershell','-NoProfile','-Command',command],check=True)
search_path=snap(); search_bytes=search_path.read_bytes();search_rows,search_text=ocr(search_path)
assert not any(v in search_text for v in ('내일 다시', '모두 사용', '한도', '오류')), 'Search limit/error: stop before preparation'
assert normalized(expected) in normalized(search_text),'Expected search result not found; inspect manually'
assert '추가' in search_text,'Add button not found; stopped'
py('followup_fresh_prepare.py')
prepared=snap();_,text=ocr(prepared)
assert normalized(expected)+'대신' in normalized(text),'Wrong target or sheet; stopped before lookup'
assert '대안 찾기' in text and '카테고리' not in text,'Search button layout not ready'
py('followup_fresh_once.py',str(n),expected)
folder=ROOT/'fresh'/f'{n:02d}'
(folder/'search.png').write_bytes(search_bytes)
(folder/'search-ocr.json').write_text(json.dumps(search_rows,ensure_ascii=False,indent=2),encoding='utf-8')
rows,text=ocr(folder/'result.png')
(folder/'result-ocr.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
assert not any(v in text for v in ('내일 다시','모두 사용','잠시 후 다시','한도','오류')),'Result error: stop path'
assert '원래 장소에서' in text,'Result not recognized; inspect without retry'
log('5586','fresh-verified',f'{n:02d} {expected}')
print('VERIFIED',n,expected)
