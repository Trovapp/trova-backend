#!/usr/bin/env python3
"""Saved evidence only: summarize current-runtime repetitions, never contact production."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics
from xml.sax.saxutils import escape


def summarize(raw):
    measured=[r for r in raw['results'] if not r.get('warmup')]
    successful=[r for r in measured if r.get('status')=='DONE' and len(r.get('drafts',[]))==1 and r['drafts'][0]['status']=='READY']
    def stats(values):
        return {'count':len(values),'median':statistics.median(values),'min':min(values),'max':max(values)} if values else {'count':0}
    metrics={
        'queueWaitMs':stats([r['queueWaitMs'] for r in successful]),
        'analysisSeconds':stats([r['createdToAnalysisDoneSeconds'] for r in successful]),
        'analysisDoneToDraftCreatedSeconds':stats([r['drafts'][0]['analysisDoneToDraftCreatedSeconds'] for r in successful]),
        'draftCreatedToLastUpdateSeconds':stats([r['drafts'][0]['draftCreatedToLastUpdateSeconds'] for r in successful]),
        'shareCreatedToDraftLastUpdateSeconds':stats([r['drafts'][0]['shareCreatedToDraftLastUpdateSeconds'] for r in successful]),
        'javaAiWindowLatencySeconds':stats([sum(log['latencyMs'] for log in r['javaAiWindowLogs'])/1000 for r in successful])}
    return {'scope':'One video/current unchanged Kafka+Spring AI; preparation excluded; no pooled historic/adoption-effect claim',
            'complete':bool(raw.get('complete') and not raw.get('errors')),'attemptedMeasured':len(measured),'successfulMeasured':len(successful),
            'failedOrIncompleteMeasured':len(measured)-len(successful),'preparationRows':sum(bool(r.get('warmup')) for r in raw['results']),
            'errors':raw.get('errors',[]),'metrics':metrics,'speedImprovementProven':False,
            'javaLogsAttribution':'time window only','draftTimingLimit':'READY last update proxy; not a dedicated generation finish timestamp'}


def write_outputs(raw_path,output_dir,pdf_path=None,font=None):
    raw_path=Path(raw_path);folder=Path(output_dir);folder.mkdir(parents=True,exist_ok=True)
    raw=json.loads(raw_path.read_text());summary=summarize(raw)
    summary['sourceSha256']=hashlib.sha256(raw_path.read_bytes()).hexdigest()
    (folder/'repeat-summary-20261102.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
    labels={'queueWaitMs':'분석 시작 전 대기(ms)','analysisSeconds':'장소 저장 완료까지(초)',
            'analysisDoneToDraftCreatedSeconds':'장소 저장 후 초안 생성까지(초)',
            'draftCreatedToLastUpdateSeconds':'초안 생성 후 마지막 갱신까지(초)',
            'shareCreatedToDraftLastUpdateSeconds':'작업 생성 후 초안 마지막 갱신까지(초)',
            'javaAiWindowLatencySeconds':'시간대 일정 AI 응답 시간 합(초)'}
    rows=[['측정 구간','표본','중앙값','최소~최대']]
    for key,label in labels.items():
        stat=summary['metrics'][key]
        rows.append([label,str(stat['count']),f"{stat['median']:.3f}" if stat['count'] else '없음',f"{stat['min']:.3f}~{stat['max']:.3f}" if stat['count'] else '없음'])
    text=['# 운영 반복 측정 결과','',
          '현재 배포의 한 영상 반복 확인입니다. Kafka 대 내부 전달 또는 Spring AI 도입 전후의 새 통제 비교는 아닙니다.',
          f"측정 시도 {summary['attemptedMeasured']}회 / 분석과 일정 모두 준비 {summary['successfulMeasured']}회 / 실패 또는 미완료 {summary['failedOrIncompleteMeasured']}회. 준비 {summary['preparationRows']}회는 통계에서 제외합니다.",
          f"실행 완료: {summary['complete']}. 오류는 원본에 남기며 미완료 결과를 성공으로 표현하지 않습니다.",'',
          '| '+' | '.join(rows[0])+' |','|---|---:|---:|---:|']
    text+=['| '+' | '.join(row)+' |' for row in rows[1:]]
    text+=['','## 각 실행','', '| 구분 | 작업 | 장소 저장까지(초) | 장소 수 | 일정 상태 |','|---|---:|---:|---:|---|']
    for row in raw['results']:
        text.append('| '+' | '.join(['준비' if row.get('warmup') else '측정',str(row['jobId']),str(row.get('createdToAnalysisDoneSeconds','미완료')),str(len(row.get('savedPlaces',[]))),','.join(d['status'] for d in row.get('drafts',[])) or '미확인'])+' |')
    text+=['','마지막 갱신은 전용 완료 시각이 아닙니다. 일정 AI 로그도 개별 영상과 직접 연결되지 않은 시간대 기록입니다. 성공한 표본만의 지연 통계와 실패/미완료 수를 함께 읽어야 합니다.',
           '같은 영상의 과거 작업 이력이 늘면 자동 초안의 중복 확인 DB 비용도 달라질 수 있습니다. 원본의 priorJobsMatchingVideoId는 이 맥락을 남긴 값이며 실제 SQL 횟수는 아닙니다.',
           '기존 1회 측정이나 과거 6건을 이 표본과 합쳐 개선률을 만들지 않습니다. 준비 후 값이 더 작아도 새 코드의 속도 향상으로 귀속하지 않습니다.','']
    (folder/'repeat-result-20261102.md').write_text('\n'.join(text))
    if pdf_path:
        from reportlab.lib import colors
        from reportlab.lib.pagesizes import A4
        from reportlab.lib.styles import ParagraphStyle
        from reportlab.pdfbase import pdfmetrics
        from reportlab.pdfbase.ttfonts import TTFont
        from reportlab.platypus import Paragraph,SimpleDocTemplate,Table,TableStyle,Spacer
        pdfmetrics.registerFont(TTFont('KR',font));style=ParagraphStyle('body',fontName='KR',fontSize=10,leading=17,wordWrap='CJK',spaceAfter=10)
        cell=ParagraphStyle('cell',parent=style,fontSize=9,leading=14)
        p=lambda s:Paragraph(escape(str(s)),cell)
        story=[Paragraph('운영 반복 측정 결과 · 2026-11-02',ParagraphStyle('title',parent=style,fontSize=21,leading=30)),Spacer(1,12)]
        for line in text[2:5]:story.append(Paragraph(escape(line),style))
        table=Table([[p(s) for s in row] for row in rows],colWidths=[236,45,85,145]);table.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),colors.HexColor('#DFEAF3')),('VALIGN',(0,0),(-1,-1),'TOP'),('TOPPADDING',(0,0),(-1,-1),8),('BOTTOMPADDING',(0,0),(-1,-1),8)]));story.extend([table,Spacer(1,16)])
        for line in text[-4:-1]:story.append(Paragraph(escape(line),style))
        pdf_path=Path(pdf_path);pdf_path.parent.mkdir(parents=True,exist_ok=True)
        SimpleDocTemplate(str(pdf_path),pagesize=A4,leftMargin=42,rightMargin=42,topMargin=40,bottomMargin=40).build(story)
    return summary


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--raw',required=True);parser.add_argument('--output-dir',required=True)
    parser.add_argument('--pdf');parser.add_argument('--font',default='/System/Library/Fonts/Supplemental/AppleGothic.ttf')
    args=parser.parse_args();print(json.dumps(write_outputs(args.raw,args.output_dir,args.pdf,args.font),ensure_ascii=False))


if __name__=='__main__':main()
