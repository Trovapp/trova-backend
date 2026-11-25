"""Summarize 30 independent UI observations; never equates them to HTTP times."""
import json
from pathlib import Path
from statistics import mean,median
ROOT=Path(__file__).resolve().parents[1]/'2026-11-25-v11-recheck-followup'
rows=[json.loads(p.read_text(encoding='utf-8')) for p in sorted((ROOT/'fresh').glob('*/timing.json'))]
assert len(rows)==30 and [r['sample'] for r in rows]==list(range(1,31))
assert len({r['place'] for r in rows})==30
regions=['서울','인천','수원','대전','춘천','청주','공주','부여','안동','영주','문경','속초','삼척','원주','충주','제천','대구','포항','울산','경주','창원','통영','거제','남해','순천','여수','광주','담양','목포','제주']
categories=['식물원','관광거리','박물관','제과점','카페','미술관','성곽','정원','제과점','사찰','테마공원','시장','레일바이크','미술관','사적공원','문화유산','시장','체험조형물','해안공원','관광거리','누각','벽화마을','전망명소','문화마을','국가정원','섬·공원','문화시설','대나무숲','역사관','차 박물관']
summary=dict(count=30,uniquePlaces=30,start=rows[0]['start'],end=rows[-1]['end'],meanObservedSeconds=round(mean(r['observedSeconds'] for r in rows),3),medianObservedSeconds=round(median(r['observedSeconds'] for r in rows),3),minObservedSeconds=min(r['observedSeconds'] for r in rows),maxObservedSeconds=max(r['observedSeconds'] for r in rows),readyCount=sum(r['buttonReadyObserved'] for r in rows),note='UI observation includes ADB/capture overhead; HTTP statistics must come from server')
(ROOT/'fresh-ui-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
lines=['# 새 원래 장소 30건 — UI 원자료', '', '한 장소당 대안 찾기 한 번, 조건 필터 없음. 분류는 시험 표본을 설명하기 위한 수동 분류이며 API 필터가 아니다. 각 장소의 검색·추가는 준비 단계다. 후보 신규 저장·임베딩 여부와 HTTP 지연은 서버 자료로 따로 확인한다.', '', f"첫 요청 {summary['start']}, 마지막 버튼 복귀 관측 {summary['end']}. UI 관측 평균 {summary['meanObservedSeconds']}초, 중앙값 {summary['medianObservedSeconds']}초, 범위 {summary['minObservedSeconds']}~{summary['maxObservedSeconds']}초. ADB 입력과 캡처 지연을 포함하며 HTTP 평균·p95가 아니다.", '', '| 번호 | 지역·표본 분류 | 실제 대상 | 시작 KST | 화면 관측 초 | 원본 |', '|---|---|---|---|---|---|']
for r,region,category in zip(rows,regions,categories):
    n=r['sample'];lines.append(f"| {n:02d} | {region}·{category} | {r['place']} | {r['start'][11:23]} | {r['observedSeconds']:.3f} | [이전](fresh/{n:02d}/before.png)·[결과](fresh/{n:02d}/result.png) |")
(ROOT/'fresh-ui-results.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print(json.dumps(summary,ensure_ascii=False))
