"""Generate offline report tables and stage evidence from saved raw data."""
import json
import re
import statistics
from summarize_v11_prom import ROOT, read, summarize

data = json.loads((ROOT/'server-deltas.json').read_text(encoding='utf-8'))
old = ROOT.parent/'2026-11-20-alternatives'
baseline = summarize(read(old/'before.prom'), read(old/'after.prom'))
previous = next(h for h in baseline['http'] if 'alternatives' in h['uri'])
previous_sql = next(h for h in baseline['sql'] if 'alternatives' in h['uri'])
assert previous['count'] == 12
assert abs(previous['meanSeconds']-2.0493783831666668) < 1e-9
assert abs(previous['p95']['estimate']-4.0802189296) < 1e-9
assert abs(previous_sql['perRequest']-320/12) < 1e-9
groups = {}
for line in (ROOT/'server/journal-steps.log').read_text(encoding='utf-8').splitlines():
    if '구간별 시간:' not in line:
        continue
    time = re.findall(r'2026-11-25T(\d\d:\d\d:\d\d\.\d+)\+09:00', line)[0]
    kind = '대안' if time < '13:00:46' else '대화'
    groups.setdefault(kind, []).append(dict(time=time, **{k:int(v) for k,v in re.findall(r'(\S+)=([0-9]+)ms',line)}))
assert [len(groups[k]) for k in ['대안','대화']] == [30,30]
(ROOT/'stage-deltas.json').write_text(json.dumps(groups, ensure_ascii=False, indent=2), encoding='utf-8')
current = next(h for h in data[1]['http'] if 'alternatives' in h['uri'])
detail = next(h for h in data[3]['http'] if h['uri']=='/api/trips/{id}')
comparison = [dict(label='대안 (12→30건)', before=dict(meanSeconds=previous['meanSeconds'],p95Seconds=previous['p95']['estimate'],sqlPerRequest=previous_sql['perRequest']), after=dict(meanSeconds=current['meanSeconds'],p95Seconds=current['p95']['estimate'],sqlPerRequest=248/30)), dict(label='상세 (24h→단기 33건)',before=dict(p95Seconds=.444280),after=dict(p95Seconds=detail['p95']['estimate']))]
(ROOT/'server-comparison.json').write_text(json.dumps(comparison, ensure_ascii=False, indent=2), encoding='utf-8')
out = ['# 서버 집계·단계 분해', '', '입력은 Mac이 제공한 16개 .prom.gz와 journal이다. 모든 시간 단위는 초, SQL은 계측된 문장 수다. 집계 스크립트는 네트워크 요청을 하지 않는다. 재현 순서는 summarize_v11_prom.py → report_v11_results.py → plot_v11_recheck.py → pdf_v11_recheck.py다.', '', '## 집계 범위와 검증', '', '모든 창의 process_start_time_seconds가 같고 선택한 누적 count·sum·bucket·total의 감소가 없다. 시작 스냅샷에 없는 시리즈는 같은 프로세스의 신규 등록으로 가정해 0에서 차감했으며 목록을 server-deltas.json에 남겼다. 11-20 원자료 재계산으로 대안 12건·평균 2.049378383초·버킷 p95 4.080218930초·SQL 26.666667을 재현했다.', '', 'HTTP는 method/uri/status로 분리한다. SQL에는 status 라벨이 없어 성공·실패가 섞인 URI는 성공 요청당 SQL로 해석하지 않는다. HTTP 완료 시점 기준이므로 SSE 수명과 창 밖에 시작한 요청이 포함될 수 있다. 비동기 분석 SQL·명시 훅 없는 JDBC는 빠진다. 외부 타이머는 호출 계측 횟수이며 실제 네트워크 재시도 횟수와 같다고 보장하지 않는다. place-embedding-batch는 문장별로 같은 배치 시간을 기록하므로 합산 시간을 실대기로 쓰지 않는다.', '', '| 경로 | 실제 before KST | 실제 after KST |', '|---|---|---|']
for w in data:
    out.append(f"| {w['route']} | {w['beforeSnapshotKST'][11:19]} | {w['afterSnapshotKST'][11:19]} |")
out += ['', '대화 창에는 마지막 대안 HTTP 1건이 겹친다. 오류 창에는 빈 시간 창의 SSE 종료가 겹치며, 빈 시간 창에는 오류 3건이 겹친다. 전용 URI로 핵심 지표를 분리했고 전체 창의 단순 합계를 전체 고유 요청 수로 사용하지 않았다.', '', '## HTTP 원합계와 p95 구간', '', '| 창·API | 상태 | count | sum 초 | 평균 초 | p95 추정 / 포함 버킷 초 |', '|---|---:|---:|---:|---:|---|']
for w in data:
    for h in w['http']:
        if not (h['uri']=='/api/trips/{id}' or any(x in h['uri'] for x in ['alternatives','messages','details','order','gap-recommendations','events','shares']) or h['status'] in ['401','404']):
            continue
        p=h['p95']; quant='없음' if p is None else (f"> {p['lower']:.3f}" if p['estimate'] is None else f"{p['estimate']:.6f} / ({p['lower']:.6f}, {p['upper']:.6f}]")
        out.append(f"| {w['route']} · {h['method']} {h['uri']} | {h['status']} | {h['count']:.0f} | {h['sumSeconds']:.6f} | {h['meanSeconds']:.6f} | {quant} |")
out += ['', '## SQL 원합계', '', '| 창·API | 계측 count | SQL sum | SQL / 계측 요청 |', '|---|---:|---:|---:|']
for w in data:
    for q in w['sql']:
        if q['uri']=='/api/trips/{id}' or any(x in q['uri'] for x in ['alternatives','messages','details','order','gap-recommendations','shares']):
            out.append(f"| {w['route']} · {q['method']} {q['uri']} | {q['count']:.0f} | {q['sum']:.0f} | {q['perRequest']:.6f} |")
out += ['', '수정 창의 gap SQL 243/30=8.1은 200 28건+429 2건을 합친 값이다. 영상 접수 SQL 30/5=6은 비동기 분석 전체 SQL이 아니다. 상세 30회 조작에 HTTP 33건이 기록되어 분모를 33으로 유지했다.', '', '## 외부 API·내부 도구 타이머', '', '| 창 | provider / operation | count | sum 초 | 평균 초 |', '|---|---|---:|---:|---:|']
for w in data:
    for t in w['timers']:
        if t['metric']=='trova_api_call_seconds':
            l=t['labels'];out.append(f"| {w['route']} | {l['provider']} / {l['operation']} | {t['count']:.0f} | {t['sumSeconds']:.3f} | {t['meanSeconds']:.4f} |")
out += ['', '위 항목은 모두 success=true다. 상세·공동 편집·읽기 오류 창의 외부 호출 증분은 0이다. 대화 창 Places 31회에는 경계의 마지막 대안 요청 1회가 섞여 있어 전부 대화에 귀속할 수 없다. internal 도구 시간은 그 안의 외부 API를 포함하므로 외부 시간과 더하지 않는다.', '', '## 영상 병목', '', '비동기 execution 5건 sum=47.1209초, 평균 9.4242초다. journal 실행 시간은 10.661·8.251·9.143·10.619·8.444초이며 대기 시간은 각각 2.568·0.255·0.279·0.292·0.331초다. 실행 평균에는 큐 대기·접수·앱 렌더링·별도 자동 초안 생성이 포함되지 않는다.', '', '| 단계 | 5건 평균 초 |', '|---|---:|']
for t in data[0]['timers']:
    if t['metric']=='trova_analysis_stage_seconds':
        out.append(f"| {t['labels'].get('stage',str(t['labels']))} | {t['meanSeconds']:.4f} |")
out += ['', 'extracting 6.924초가 실행 평균의 약 73%다. 그 안의 수집 3.533초+필터 2.244초가 주요 비용이고 두 값을 extracting에 다시 더하면 중복이다. 과거 수집 13초+필터 14초보다 낮은 관측이나 영상·입력 차이로 직접 개선율은 없다. 첫 작업 Python 시작·종료 1.386초와 대기 2.568초가 커서 별도 초기 실행 비용도 보인다. 4번 영상은 서버 실행 10.619초, 화면 목록 16.369초이나 두 시계의 시작점이 달라 차이를 순수 네트워크 시간으로 볼 수 없다.', '', '## 대안·대화 단계 로그', '', '| 경로 (각 30건) | 단계 | 평균 ms | 최대 ms |', '|---|---|---:|---:|']
for kind,rows in groups.items():
    for key in rows[0]:
        if key!='time':out.append(f"| {kind} | {key} | {statistics.mean(x[key] for x in rows):.1f} | {max(x[key] for x in rows)} |")
out += ['', '대안의 느린 첫 요청(12:52:42.493 완료 로그)은 검색 272ms·카탈로그 214ms·임베딩 1749ms·개인화 200ms였다. 이후 반복 캐시의 영향이 커서 신규 장소 배치 저장 효과만 분리할 수 없다. 단계 합계는 HTTP 전체와 경계가 다르며 남은 시간을 전부 SQL로 단정하지 않는다.', '', '대화 도구 평균은 50.817/30=1.694초, Gemini 전후 생성 합은 42.958/30=1.432초다. 둘을 빼면 HTTP 평균의 나머지는 약 0.491초지만 순차 경계·기타 처리 시간이 섞인 잔차다. 도구 안 실내 태그 단계 평균 987ms가 크고, 13:17:22.699 로그에서 태그 2899ms가 관찰됐다. 후보 1곳 17건·여러 곳 11건·없음 2건은 화면으로 분류했으며 집계 타이머에 후보 수 라벨이 없어 그룹별 전체 HTTP 평균·p95를 복원할 수 없다. 전체 30건을 한 집계로 보고하고 질의 임베딩 11회만 별도로 확인했다.', '', '## 상세·수정·공동 편집·빈 시간', '', '상세 자체는 평균 123ms·SQL 2지만 진입 때 추천·목록·멤버 등 다른 요청이 동반된다. 반복 진입 중 SSE 429 24건이 관찰됐다. 메모 평균 343ms, 순서 784ms이며 SQL은 4와 6.13으로 다르다. 수정 창 자동 추천은 성공 28건·429 2건, Places 검색 43회로 쓰기 외 비용이 더해졌다. 메모가 추천 입력을 바꾸지 않아도 재요청한다면 무효화 범위를 줄일 여지가 있다.', '', '공동 편집 메모 17건은 평균 389ms, A/B 상세 재조회는 34건, 자동 추천은 429 17건이었다. 화면은 17번 모두 전달됐고 유효 15건의 첫 변화 관찰 평균 2.427초다. SSE 활성 수 미계측과 ADB 관찰 지연 때문에 HTTP 389ms와의 차이를 Kafka 지연으로 설명할 수 없다.', '', '빈 시간 전용 창 추천 30건은 평균 723ms·SQL 6·Places 30회(합 2.982초)였다. 외부 검색 평균 약 99ms 외에 카탈로그·추천 구성·DB 등 비용이 남지만 상세 단계 타이머가 없어 잔여 전체를 특정 원인으로 배정하지 않는다. 이 창의 429 27건은 모두 SSE이며 추천 실패가 아니다. 앞선 상세 창의 빈 시간 평균 49ms는 일차별 장소가 하나여서 실제 추천 조건이 달라 직접 비교하지 않는다.', '', '## 오류·연결 해석', '', '401 2건은 HTTP uri=UNKNOWN으로 기록되고 404 2건은 /api/public/trips/{token}으로 기록된다. 네 traceId가 journal의 상태와 일치한다. 경로 템플릿의 {token}은 실제 공유 비밀값이 아니다. 전체 제공 창에서 HTTP 5xx 증분은 0이다. journal의 웹 푸시 외부 400은 별도 외부 응답으로 HTTP 5xx와 혼동하지 않는다.', '', 'SSE 429 총 51건과 추천 429 19건은 정상 UI 사용 중 발생했다. TripStreams의 사용자별 동시 연결 기본 3개 제한과 DailyQuotaService의 일일 장소 호출 기본 100회 제한은 조사할 코드 경로이나 이 로그만으로 원인을 확정할 수 없다. 연결 해제 지연·재연결 중복·추천 재조회 조건을 같은 요청 흐름에서 후속 검증해야 한다. 상한을 무조건 늘리는 해결은 제안하지 않는다.', '', '모든 경계의 Kafka lag·deferred size는 0, deferred discard 차분도 0이다. fallback 카운터와 SSE 활성 gauge가 없으므로 미측정으로 남긴다. SSE 200 완료 요청은 상세 창 26건 평균 119.034초, 빈 시간 창 24건 평균 51.428초이며 두 창의 p95 rank가 +Inf 버킷 안에 있어 30초 초과만 알 수 있다. Prometheus histogram_quantile은 이 경우 마지막 유한 경계를 반환하므로 Grafana 30초 선은 정확한 p95나 연결 종료 시간이 아니다. 매니저 원자료 README의 “30초 연결 종료” 해석은 이 차분 근거로 정정한다.']
(ROOT/'server-results.md').write_text('\n'.join(out)+'\n', encoding='utf-8')
print('Historical fixture and 60 stage logs validated; report tables generated.')
