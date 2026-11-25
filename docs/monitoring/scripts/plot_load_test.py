"""Render isolated measurements; no network access."""
import json,re
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
ROOT=Path(__file__).resolve().parents[1]/'2026-11-25-load-test';RAW=ROOT/'raw'
font=Path('/System/Library/Fonts/Supplemental/AppleGothic.ttf')
if font.exists():fm.fontManager.addfont(str(font));plt.rcParams['font.family']=fm.FontProperties(fname=str(font)).get_name()
plt.rcParams['axes.unicode_minus']=False
plt.rcParams.update({'font.size':14,'axes.titlesize':15,'axes.labelsize':14,'xtick.labelsize':12,'ytick.labelsize':12})
LABELS={'detail':'조회','memo':'메모','order':'순서','live':'동시 수정','alternatives':'대안','conversation':'대화','gap':'빈 시간'}
rows=json.loads((RAW/'runs.json').read_text())
fig,axes=plt.subplots(1,3,figsize=(15,4.7))
for mode in ['detail','memo','order','live']:
 data=[r for r in rows if r.get('phase')=='steady' and r['mode']==mode and 'recovery' not in r['label'] and r.get('p95_ms') is not None]
 if not data:continue
 latest={r['vus']:r for r in data};data=[latest[v] for v in sorted(latest)]
 x=[r['vus'] for r in data]
 axes[0].plot(x,[r['p95_ms']/1000 for r in data],'o-',label=LABELS.get(mode,mode))
 axes[1].plot(x,[r['rps'] for r in data],'o-',label=LABELS.get(mode,mode))
 axes[2].plot(x,[r['errors']*100 for r in data],'o-',label=LABELS.get(mode,mode))
axes[0].axhline(1,color='red',ls='--',label='p95 기준 1초');axes[2].axhline(1,color='red',ls='--')
for ax,title,y in zip(axes,['지연 한계','처리량 포화','오류'],['p95(초)','전체 HTTP RPS','측정 오류율(%)']):
 ax.set(xlabel='VU (요청 뒤 1초 대기)',ylabel=y,title=title);ax.grid(alpha=.25);ax.legend(fontsize=11)
fig.suptitle('격리 복제 · 0.125 CPU / 768MiB / G1 힙 144MiB · VU별 최후 관측');fig.tight_layout();fig.savefig(ROOT/'limits.png',dpi=170);plt.close(fig)
fig,axes=plt.subplots(4,1,figsize=(12,10),sharex=True)
from datetime import datetime,timezone,timedelta
start=min(r['start'] for r in rows)
for ax,name,mult,y in zip(axes,['cpu','heap','db_pending','gc_max'],[100,1/1048576,1,1000],['JVM 보고 CPU (%)','힙 (MiB)','DB 연결 대기','GC 최대 rolling (ms)']):
 p=RAW/('prom-'+name+'.json')
 if p.exists():
  data=json.loads(p.read_text())['data']['result']
  for s in data:
   values=[(float(t),float(v)) for t,v in s.get('values',[]) if v not in ['NaN','+Inf','-Inf']]
   ax.plot([(t-start)/60 for t,v in values],[v*mult for t,v in values],lw=1)
 ax.set_ylabel(y);ax.grid(alpha=.25)
 for r in rows:
  if 'recovery' not in r['label']:ax.axvspan((r['start']-start)/60,(r['end']-start)/60,alpha=.035,color='navy')
axes[-1].set_xlabel('첫 부하 시작 이후 분 (15초 수집 / 3초 query resampling)');fig.suptitle('같은 시각의 서버 자원 · Prometheus 원자료');fig.tight_layout();fig.savefig(ROOT/'resources.png',dpi=170);plt.close(fig)
ext=[r for r in rows if r['mode'] in ['alternatives','gap','conversation','mixed'] and r.get('p95_ms') is not None]
if ext:
 fig,axes=plt.subplots(1,2,figsize=(12,4.5))
 for mode in ['alternatives','conversation','gap']:
  latest={r['vus']:r for r in ext if r['mode']==mode}
  data=[latest[v] for v in sorted(latest)]
  if not data:continue
  axes[0].plot([r['vus'] for r in data],[r['p95_ms']/1000 for r in data],'o-',label=LABELS.get(mode,mode))
  axes[1].plot([r['vus'] for r in data],[r['rps'] for r in data],'o-',label=LABELS.get(mode,mode))
 axes[0].axhline(10,c='red',ls='--',label='외부 경로 중단 10초')
 axes[0].axhline(1,c='orange',ls=':',label='일반 API 목표 1초(참고)')
 for ax in axes:ax.grid(alpha=.25);ax.legend(fontsize=11);ax.set_xlabel('VU (요청 뒤 3초 대기)')
 axes[0].set_ylabel('p95(초)');axes[1].set_ylabel('전체 HTTP RPS')
 fig.suptitle('실제 외부 API · VU별 최후 관측 · 첫 호출/카탈로그 상태는 표 참조');fig.tight_layout();fig.savefig(ROOT/'external.png',dpi=170);plt.close(fig)
if (RAW/'sse-results.json').exists():
 sse=json.loads((RAW/'sse-results.json').read_text());labels=[s['label'] for s in sse];ok=[];bad=[];active=0
 for s in sse:
  accepted=sum(x['status']==200 for x in s['results']);rejected=sum(x['status']==429 for x in s['results'])
  if s['label'].startswith('accumulated-'):active+=accepted;ok.append(active)
  else:ok.append(accepted)
  bad.append(rejected)
 fig,ax=plt.subplots(figsize=(12,5));ax.bar(labels,ok,label='200 연결 허용');ax.bar(labels,bad,bottom=ok,label='429 제한');ax.set(ylabel='연결 시도 수',title='SSE 동시 연결 제한과 닫은 연결 정리');ax.tick_params(axis='x',rotation=35);ax.legend();fig.tight_layout();fig.savefig(ROOT/'sse.png',dpi=170);plt.close(fig)
print('PNG 차트 생성 완료')

calibration=[next((r for r in rows if r['label']==label),None) for label in ['detail-1-3','memory768-scrape3s-detail1','detail-1-8']]
if all(calibration):
 fig,ax=plt.subplots(figsize=(9,4));ax.bar(['520MiB / 3초 수집','768MiB / 3초 수집','768MiB / 15초 수집'],[r['p95_ms']/1000 for r in calibration],color=['#a3483b','#d4924b','#256c80']);ax.axhline(1,c='red',ls='--');ax.set(ylabel='조회 1 VU p95(초)',title='같은 JVM 순차 조건 변경 · 추가 예열 혼입으로 인과적 개선율 아님');fig.tight_layout();fig.savefig(ROOT/'calibration.png',dpi=170);plt.close(fig)
