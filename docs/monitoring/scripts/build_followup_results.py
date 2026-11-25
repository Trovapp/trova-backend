"""Build reviewable tables from recorded UI timing and server evidence (offline)."""
import json
import re
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / '2026-11-25-v11-recheck-followup'
ocr = json.loads((ROOT/'screen-text.json').read_text(encoding='utf-8-sig'))
errors = []
for line in (ROOT/'server/journal.log').read_text(encoding='utf-8').splitlines():
    match = re.search(r'(2026-11-25T\d+:\d+:\d+\.\d+\+09:00).*status=(\d+)', line)
    if match:
        errors.append((datetime.fromisoformat(match[1]), int(match[2])))
orders = ['T,B,L','T,L,B','L,T,B','T,L,B','T,B,L','T,L,B','T,B,L','T,L,B','T,B,L','T,L,B']
def words(sample, phase, device):
    return [row['text'] for row in ocr.get(f'{sample}/{phase}-{device}.png', [])]
def listed(sample, phase, device):
    lines = words(sample,phase,device)
    result = []
    for line in lines:
        if '저장한 장소' in line: break
        for name,key in [('이삭토스트','T'),('연화정 도서관','L'),('광안리해수욕장','B')]:
            if line.startswith(name) and key not in result: result.append(key)
    return ','.join(result)
def saved(sample, phase, device, value):
    return any(value in line and (line.startswith('/-') or line.startswith('%-')) for line in words(sample,phase,device))
records=[]
for kind in ('memo','order','delete','offline'):
    for n in range(1,11):
        sample=f'{kind}-{n:02d}'
        data=json.loads((ROOT/'observations'/sample/'timing.json').read_text())
        a,b=data['actions']; start=min(datetime.fromisoformat(a['dispatch']),datetime.fromisoformat(b['dispatch']))
        final = ('A' if n==6 else 'B')+f'-memo-{n:02d}' if kind=='memo' else orders[n-1] if kind=='order' else (f'A-delete-{n:02d}; 장소 유지' if n in (5,8) else '장소 삭제') if kind=='delete' else f'B-offline-{n:02d}; A 초안 유지'
        expected = '200/409' if kind=='memo' and n==6 else '409/200' if kind=='memo' else '409/200' if kind=='order' else '200/409' if kind=='delete' and n in (5,8) else '404/204' if kind=='delete' else '오프라인 실패→수동409 / 200'
        matching=[dict(time=t.isoformat(timespec='milliseconds'),status=c) for t,c in errors if 0 <= (t-start).total_seconds() <= 5 and c in (404,409)]
        candidate=None
        if kind!='offline':
            for phase in ('after-01s','after-03s','after-06s','after-10s'):
                if kind=='memo': ok=all(saved(sample,phase,d,final) for d in ('5584','5586'))
                elif kind=='order': ok=all(listed(sample,phase,d)==final for d in ('5584','5586'))
                elif n not in (5,8): ok=all(listed(sample,phase,d)=='L,B' for d in ('5584','5586'))
                else: ok=all(saved(sample,phase,d,f'A-delete-{n:02d}') for d in ('5584','5586'))
                if ok:
                    cap=[x for x in data['observations'] if x['phase']==phase]
                    upper=max((datetime.fromisoformat(x['captureEnd'])-start).total_seconds() for x in cap)
                    candidate=dict(phase=phase,upperSeconds=round(upper,3));break
        records.append(dict(sample=sample,a=a['dispatch'],b=b['dispatch'],dispatchSkewMs=round(abs((datetime.fromisoformat(a['dispatch'])-datetime.fromisoformat(b['dispatch'])).total_seconds()*1000),3),final=final,statusInferredAB=expected,errorJournalWithin5s=matching,convergence=candidate,version='미확인',activity='미확인'))
(ROOT/'trial-results.json').write_text(json.dumps(records,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
lines=['# 40회 시도별 결과', '', '시각은 KST 호스트 ADB dispatch이다. 응답 A/B는 시각별 오류 로그·구간별 HTTP 집계·화면 결과를 대조한 **귀속 추정**이며, 요청 ID를 기기에 연결한 패킷 기록은 없다. 버전 숫자·activity 행은 40회 모두 미확인이다. 서버 집계는 별도 창 전체 요청을 포함한다.', '', '수렴 상한은 두 화면에 동일한 저장값/순서/삭제가 처음 확인된 캡처의 **종료 시각 − 빠른 dispatch**이다. 실제 완료 시간보다 큰 관측 상한이며 API 지연이나 SSE p95가 아니다. 초안은 저장값과 별도로 남을 수 있다. OCR은 원본 스크린샷과 함께 검토한다.', '', '| 시도 | A 입력 | B 입력 | 최종 저장 상태 | 응답 A/B 추정 | 수렴 관측 | 버전·activity |', '|---|---|---|---|---|---|---|']
for r in records:
    conv=(f"≤{r['convergence']['upperSeconds']:.3f}초" if r['convergence'] else ('>60초(추가 관찰)' if r['sample']=='offline-10' else '>20초; 수동 재시도 후 일치') if r['sample'].startswith('offline') else '최종10초 캡처 일치; 정밀 판정 보류')
    evidence=f"[A](observations/{r['sample']}/after-10s-5584.png)·[B](observations/{r['sample']}/after-10s-5586.png)"
    lines.append(f"| {r['sample']} {evidence} | {r['a'][11:23]} | {r['b'][11:23]} | {r['final']} | {r['statusInferredAB']} | {conv} | 미확인 |")
lines += ['', '순서 기호: T=이삭토스트 명동성당점, L=연화정 도서관, B=광안리해수욕장. offline 표의 409는 연결 복구 후 별도 수동 저장 재시도이며 최초 오프라인 저장의 HTTP 응답이 아니다.', '', '삭제 05·08의 B에서는 두 건 모두 after-03s 캡처에서 친구가 방금 바꿨다는 충돌 안내를 확인했다. 1초 캡처만으로 안내가 없다고 결론 내리지 않는다.']
(ROOT/'conflict-results.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print('40 rows; candidates:',sum(r['convergence'] is not None for r in records))
print('Unresolved:',[r['sample'] for r in records if not r['sample'].startswith('offline') and r['convergence'] is None])
