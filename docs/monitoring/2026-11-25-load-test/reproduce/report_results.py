"""Final Korean result tables from saved evidence; no network or credentials."""
import json,re,math,gzip
from pathlib import Path
from datetime import datetime,timezone,timedelta
HERE=Path(__file__).resolve().parent;ROOT=HERE.parent;RAW=ROOT/'raw'
rows=json.loads((RAW/'runs.json').read_text());usage=json.loads((RAW/'external-usage.json').read_text())
KST=timezone(timedelta(hours=9))
def kst(t):return datetime.fromtimestamp(t,KST).strftime('%H:%M:%S')
def f(x,n=2):return '미제공' if x is None else f'{x:.{n}f}'
def met(r):return json.loads((RAW/(r['label']+'.json')).read_text())['metrics']
def countstats(s):return {k:int(v) for k,v in re.findall(r'^(\w+) (\d+)$',s,re.M)}
def cpu(r,service='app'):
 a=countstats(r['resources_before'][service]);b=countstats(r['resources_after'][service]);dt=r['end']-r['start']
 return (b['usage_usec']-a['usage_usec'])/1e6/dt,100*(b['nr_throttled']-a['nr_throttled'])/max(1,b['nr_periods']-a['nr_periods'])
def peak(name,r):
 p=RAW/('prom-'+name+'.json')
 if not p.exists():return None
 vals=[float(v) for s in json.loads(p.read_text())['data']['result'] for t,v in s.get('values',[]) if r['start']<=float(t)<=r['end'] and v not in ['NaN','+Inf','-Inf']]
 return max(vals) if vals else None
pauses=json.loads((RAW/'slow-request-gc-overlap.json').read_text())['pauses']
def gc(r):return max((g['ms'] for g in pauses if r['start']<=g['end']<=r['end']),default=0)
def bylabel(label):return next(r for r in rows if r['label']==label)
summary=['## 최종 결과 — 조건을 붙인 한계','',f"실제 부하 기록은 KST {kst(json.loads((RAW/'runs-520MiB.json').read_text())[0]['start'])}~{kst(rows[-1]['end'])}다. 그 전 16:29부터 앱을 기동했다. 후기 일반 API는 앱 0.125 CPU·768MiB·G1 힙 144MiB·15초 수집 조건이다. 아래 ‘최고 통과 관측’은 단기 검사이며 장시간 안정 용량이 아니다.",'','| 경로 | 최고 통과 관측 (VU / RPS / p95) | 같은 VU의 반복 실패 (VU / RPS / p95) | 평가 오류율 | 먼저 확인된 제약 |','|---|---|---|---|---|']
case_labels={'상세 GET':('late-detail-20-26','late-detail-40-27','confirm-late-detail-40-28'),'메모 PATCH':('confirm-late-memo-5-32','late-memo-10-33','confirm-late-memo-10-34'),'순서 PATCH':('confirm-late-order-10-40','late-order-20-41','confirm-late-order-20-42')}
for name,(p,a,b) in case_labels.items():
 p,a,b=map(bylabel,[p,a,b]);summary.append(f"| {name} | {p['vus']} / {p['rps']:.2f} / {p['p95_ms']/1000:.3f}초 | {a['vus']} / {a['rps']:.2f}→{b['rps']:.2f} / {a['p95_ms']/1000:.3f}→{b['p95_ms']/1000:.3f}초 | 0% | 앱 CPU 제한·대기. DB 자체 CPU는 여유 |")
summary+=['','최초 5 VU·10 VU 실패가 재측정에서 통과한 구간도 있다. 최고 통과 관측을 “항상 이 인원까지 안정”으로 바꾸지 않는다. 처리량 최대를 끝까지 밀어 측정한 값이 아니라 p95 1초 목표를 반복해서 못 맞춘 단계다.','', '| 외부 경로 | 상승 허용 관측 | 중단 단계·상태 | 해석 |','|---|---|---|---|', '| 대안 | 32 VU, 3.87 RPS, p95 7.08초 | 64 VU, 5.20 RPS, p95 14.82초, 전체 2/168 HTTP 429 | 10초 실험 기준 초과. 429는 누적 일일 앱 quota이며 Google 제공자 오류 아님 |', '| 말로 바꾸기 | 1 VU, 0.097 RPS, p95 8.92초 | 2 VU, 0.249 RPS, p95 8.98초, 내용 실패 1/5 | Gemini 429/RESOURCE_EXHAUSTED. 모델별 무료 분당 15회 한도가 서버 자원보다 먼저 확인됨 |', '| 빈 시간 | 16 VU, 3.86 RPS, p95 2.98초 | 32 VU, 3.95 RPS, p95 11.25초, 오류 0% | 1~4개 빈 시간 혼합 데이터. 같은 빈 시간 수에서 VU만 바꾼 상한으로 해석 금지 |', '', '**외부 표의 상승 기준은 10초이며 일반 API 목표 1초와 다르다.** “대안 32 VU 통과”는 좋은 사용자 경험을 뜻하지 않는다. 대화는 1 VU부터 일반 API의 1초 목표를 넘는다.','',f"**외부 사용량:** Places **{usage['ledger']['places']:,}회 / 2,000회 이하**, Gemini 실제 HTTP 시도 **{usage['gemini_http_attempts']}회**(배치 7회·단일 임베딩 5회·대화 생성 23회, 그중 한도 실패 1회). Gemini 지표 108단위는 배치 입력 80문장을 중복 계산하므로 108회 HTTP라고 쓰지 않는다. 카카오는 별도 의존성 검사 완료 1회와 전송 여부 미확정 0~1회를 구분한다.",'','Gemini 최초 한도 로그는 KST **17:26:15.951**, `gemini-3.5-flash-lite`, `GenerateRequestsPerMinutePerProjectPerModel-FreeTier`, 응답 `quotaValue=15`, 재시도 안내 약 43초였다. 이 경로는 이후 재시도하지 않았다. 직전 두 단계 약 53초 구간에 대화 생성 완료 기록 15회(실패 포함)가 있었고, 중단 뒤 in-flight 완료 2회가 최종 합계에 추가됐다. 완료 시점 집계와 제공자의 분 경계·프로젝트 전체 사용량은 다르다. 2 VU 단계의 완료 5개 응답은 전부 HTTP 200인데 1개가 fallback이므로 HTTP 5xx만으로는 이 한도를 발견하지 못한다.','', '정상 공동 편집은 **멤버 10명 공유 여행의 SSE 10개 + 개인 여행 SSE 54개 = 전체 64개**다. 작성자 1 VU는 0.889 RPS·p95 0.689초·오류 0%, 작성자 3 VU는 2.783 RPS·p95 0.294초였다. 평가 구간은 1/70=1.43%, 전체 구간은 5/100=5%의 HTTP 409 충돌이며 5xx는 없었다. 버전 조건 헤더 없는 동시 PATCH이고 충돌 시 자동 재시도하지 않았다. 시간·예열·GC 상태가 달라 3 VU가 1 VU보다 빠르다는 개선율을 주장하지 않는다.','', '**합성 데이터 보정:** 초기 공유 여행 64명은 `TripMembershipService.java:46`의 정상 참여 상한 10명을 넘은 인위적 스트레스 조건이었다. 그 쓰기 결과는 `domain-stress-64-members`로 보존하고 위 정상 정책 결과와 분리했다. 초기 64인 조건의 1 VU p95 1.10초·3 VU 평가 오류 11.11%를 제품의 정상 용량으로 쓰지 않는다. 초기 seed와 실제 보정 이력은 별도로 기록했다.','', '**빈 시간 데이터의 한계:** 순서 PATCH가 방문 시간을 다시 맞춘 뒤 외부 후기 검사에 들어가, 사용자 1~20은 30분 초과 내부 공백 없이 마지막 일정 뒤 한 구간, 21~32는 원래 네 구간이 남았다. 따라서 16→32 VU에는 요청당 외부 호출 수 변화가 섞였다. 초기 4구간 고정의 1·2 VU 원본과 후기 1~4구간 혼합 원본을 구분하며, 고정 4구간의 최대 VU는 이번 결과로 확정하지 않는다.','', '로컬 Grafana는 기동했으나 Headless Chrome이 DevTools 포트를 만들지 못해 새 UI 캡처는 남기지 못했다. 저장한 Prometheus JSON과 생성한 PNG를 본 측정의 시각 자료로 쓴다. 아래 운영 Grafana 캡처는 **앞선 v11 재측정 12:35~14:12 KST의 참고 자료**이며 이번 부하의 캡처가 아니다.','', '![이전 운영 4xx 관측 — 이번 격리 부하와 분리](../2026-11-25-v11-recheck/server/grafana/panel-19-4xx-5xx.png)','']
resource=['## 원인 분해 — 후기 반복 실패 시각','', '| 단계 | 앱 실제 CPU 코어 (상한 0.125) | throttled 기간 % | DB CPU 코어 (상한 0.75) | 힙 최대 MiB | GC pause 최대 ms | DB active / pending 관측 최대 |','|---|---:|---:|---:|---:|---:|---|']
for labels in case_labels.values():
 for label in labels[1:]:
  r=bylabel(label);c,t=cpu(r);d,_=cpu(r,'db');heap=peak('heap',r);active=peak('db_active',r);pending=peak('db_pending',r)
  resource.append(f"| {label} | {c:.3f} | {t:.1f} | {d:.3f} | {f(heap/1048576 if heap is not None else None)} | {gc(r):.2f} | {f(active,0)} / {f(pending,0)} |")
resource+=['','앱 CPU는 후기 조회 40 VU에서 약 0.122 코어를 쓰고 cgroup 기간의 약 97~99%가 throttled였다. DB 자체 CPU가 여유인 상태에서 작은 앱 CPU의 요청 처리·GC·지표 수집이 먼저 경쟁한다. DB pool 대기가 관측되더라도 원격 DB 자체가 포화됐다는 증거로 바꾸지 않는다. 대화 2 VU는 반대로 제공자가 명시한 모델 quota가 직접 증거다.','', 'GC 로그는 pause 종류·시각과 slow request를 대조했다. [느린 요청과 GC 겹침 원본](raw/slow-request-gc-overlap.json)과 [마스킹 스레드 덤프](raw/thread-dump-scrape3s.log.gz)를 보관한다. 덤프는 3초 수집 조건에서 얻었으며 후기 40 VU 순간의 덤프라고 주장하지 않는다. Tomcat busy gauge는 제공되지 않아 JVM 전체 live thread 수로 대체하지 않는다. 힙 외 메모리의 항목별 분해(NMT)는 수행하지 않았다.','']
# Generator evidence: only recorded samples, not synthetic values.
cpus=[];mems=[]
for r in rows:
 for sample in r.get('generator',[]):
  for line in sample['stats'].splitlines():
   try:q=json.loads(line);cpus.append(float(q['CPUPerc'].rstrip('%')));mems.append(q['MemUsage'])
   except Exception:pass
resource += [f"k6는 1 CPU·512MiB 상한이며 저장된 표본의 최대 CPU는 {max(cpus):.2f}%/1코어였다. 모든 단계의 발생기 표본을 runs.json에 보관했고 SSE 발생기는 별도 1 CPU·256MiB로 제한했다. constant-VU는 고정 도착률 실행기가 아니므로 dropped-iterations를 계획된 트래픽 손실 지표로 쓰지 않는다. CPU 여유와 실제 반복 완료·연결 admission을 함께 확인했다.",'']
(RAW/'result-summary-data.json').write_text(json.dumps({'measurement_start':json.loads((RAW/'runs-520MiB.json').read_text())[0]['start'],'measurement_end':rows[-1]['end'],'generator_cpu_peak_pct':max(cpus),'places':usage['ledger']['places'],'gemini_http_attempts':usage['gemini_http_attempts']},indent=2))
# Keep report generation idempotent.
p=ROOT/'README.md';text=p.read_text();text=re.sub(r'<!-- SUMMARY START -->.*?<!-- SUMMARY END -->\n*','',text,flags=re.S);text=re.sub(r'<!-- RESULTS START -->.*','',text,flags=re.S)
anchor='## 먼저 구분할 결과';text=text.replace(anchor,'<!-- SUMMARY START -->\n'+'\n'.join(summary)+'\n<!-- SUMMARY END -->\n\n'+anchor,1)
# Append the clean numeric table; verbose HTTP label deltas stay in the linked raw file.
table=(ROOT/'stage-tables.md').read_text().split('## HTTP 상태별')[0]
text+='\n<!-- RESULTS START -->\n'+'\n'.join(resource)+'\n'+table+'\n<!-- RESULTS END -->\n';p.write_text(text)
print('README 최종 수치·원인 표 갱신')
