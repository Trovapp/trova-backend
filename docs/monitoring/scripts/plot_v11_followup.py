"""Static figures from recorded evidence; no backend calls."""
import gzip
import json
import re
from collections import Counter
from datetime import datetime
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib import font_manager

ROOT=Path(__file__).resolve().parents[1]/'2026-11-25-v11-recheck-followup'
OUT=ROOT/'charts'; OUT.mkdir(exist_ok=True)
font_manager.fontManager.addfont('C:/Windows/Fonts/malgun.ttf')
plt.rcParams.update({'font.family':'Malgun Gothic','axes.unicode_minus':False,'figure.dpi':140})
rows=json.loads((ROOT/'trial-results.json').read_text(encoding='utf-8'))
fig,ax=plt.subplots(figsize=(9,4))
for kind,label,color in [('memo','메모','#177e89'),('order','순서','#d87920'),('delete','삭제·메모','#6957a5')]:
    group=[r for r in rows if r['sample'].startswith(kind)]
    ax.plot(range(1,11),[r['convergence']['upperSeconds'] for r in group],marker='o',label=label,color=color)
ax.set(xlabel='시도 번호',ylabel='호스트 입력→양쪽 캡처 종료 (초)',title='양쪽 저장 상태 일치의 관측 상한 — API 지연이 아님',ylim=(0,6),xticks=range(1,11))
ax.legend();ax.grid(alpha=.2);fig.tight_layout();fig.savefig(OUT/'convergence-upper-bounds.png');plt.close(fig)
counts=Counter()
for line in (ROOT/'server/journal.log').read_text(encoding='utf-8').splitlines():
    m=re.search(r'T(16:22:\d+)\.\d+\+09:00.*status=401',line)
    if m:counts[m[1]]+=1
fig,ax=plt.subplots(figsize=(9,3.5)); keys=sorted(counts)
ax.bar([s[6:] for s in keys],[counts[s] for s in keys],color='#b94b41')
ax.set(xlabel='16:22분 내 초 (KST)',ylabel='401 로그 건수',title=f'인증 실패 집중: {sum(counts.values())}건 — 기기·요청 경로 미확인')
fig.tight_layout();fig.savefig(OUT/'auth-burst.png');plt.close(fig)
fig,ax=plt.subplots(figsize=(8,3.5))
ax.barh(['메모 충돌 안내/초안 유지','순서 최종 일치','삭제 성공 / 수정404','삭제409 / 메모 저장','오프라인 복구20초 내 일치','오프라인 수동 재시도 후 일치'],[10,10,8,2,0,10],color=['#177e89']*4+['#b94b41','#177e89'])
ax.set(xlabel='관찰 횟수 (각 경우 10회)',xlim=(0,11),title='공동 편집 화면 관찰 요약')
fig.tight_layout();fig.savefig(OUT/'outcomes.png');plt.close(fig)

def montage(name,items):
    fig,axes=plt.subplots(1,len(items),figsize=(4*len(items),8))
    for ax,(path,label) in zip(axes,items):
        ax.imshow(plt.imread(ROOT/path));ax.set_title(label,fontsize=11);ax.axis('off')
    fig.tight_layout();fig.savefig(OUT/name,dpi=180);plt.close(fig)
montage('memo-conflict.png',[(Path('observations/memo-10/after-03s-5584.png'),'A: B 저장값 + A 초안·충돌 안내'),(Path('observations/memo-10/after-03s-5586.png'),'B: 저장된 B-memo-10')])
montage('delete-conflict.png',[(Path('observations/delete-05/after-03s-5586.png'),'삭제 05: B에게 충돌 안내'),(Path('observations/delete-08/after-03s-5586.png'),'삭제 08: B에게 충돌 안내')])
montage('offline-recovery.png',[(Path('observations/offline-10/reconnect-60s-5584.png'),'연결 복구60초: 이전 값·오류 유지'),(Path('observations/offline-10/retry-5584.png'),'수동 재시도: 최신 값·충돌 안내')])

def metrics(prefix):
    path=next((ROOT/'server/prom').glob(prefix+'*'))
    return {l.rsplit(' ',1)[0]:float(l.rsplit(' ',1)[1]) for l in gzip.decompress(path.read_bytes()).decode().splitlines() if l and not l.startswith('#')}
b,a=metrics('before'),metrics('after')
external={}
for k,v in a.items():
    if k.startswith('trova_api_call_seconds_count'):
        provider=re.search(r'provider="([^"]+)"',k)[1]
        external[provider]=external.get(provider,0)+v-b.get(k,0)
summary=dict(heapMiB=[round(sum(v for k,v in s.items() if k.startswith('jvm_memory_used_bytes') and 'area="heap"' in k)/1048576,2) for s in (b,a)],gcCountDelta=sum(v-b.get(k,0) for k,v in a.items() if k.startswith('jvm_gc_pause_seconds_count')),gcSecondsDelta=round(sum(v-b.get(k,0) for k,v in a.items() if k.startswith('jvm_gc_pause_seconds_sum')),3),externalCallCounterDelta=external,authBurstCount=sum(counts.values()))
(ROOT/'metric-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('6 figures and metric summary generated')
