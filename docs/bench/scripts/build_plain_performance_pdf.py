#!/usr/bin/env python3
"""Readable Korean report from saved evidence only. No API/server/DB access."""
import argparse
import hashlib
import json
from pathlib import Path
from xml.sax.saxutils import escape
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import Paragraph, SimpleDocTemplate, Table, TableStyle, Spacer, PageBreak

ROOT=Path(__file__).resolve().parents[3]
B=ROOT/'docs/bench'
INK='#19324B';BLUE='#2166A2';PALE='#EFF5FA';GRAY='#546575'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--font',default='/System/Library/Fonts/Supplemental/AppleGothic.ttf')
    parser.add_argument('--output',type=Path,default=B/'reports/trova-plain-performance-20261101.pdf')
    args=parser.parse_args()
    files={name:B/'results'/name for name in ['operational-before-20261101.json','operational-after-20261101.json','production-queue-expanded-20261101.json','client-latency-expanded-20261101.json','operational-ai-supplement-20261101.json']}
    raw={name:json.loads(path.read_text()) for name,path in files.items()}
    before=raw['operational-before-20261101.json'];after=raw['operational-after-20261101.json']
    old=raw['production-queue-expanded-20261101.json'];client=raw['client-latency-expanded-20261101.json']
    assert before['complete'] and after['complete'] and not before['errors'] and not after['errors']
    assert all(x['cleanup']['residualPlaces']==x['cleanup']['residualDrafts']==0 for x in [before,after])
    assert len(before['results'])==len(after['results'])==1
    pre,post=before['results'][0],after['results'][0]
    assert pre['videoId']==post['videoId'] and pre['observedPath']==post['observedPath']=='kafka'
    assert pre['status']==post['status']=='DONE'
    import statistics
    med=lambda mode,key:statistics.median(r[key] for r in old['results'] if r['mode']==mode and not r['warmup'])
    assert sum(not r['warmup'] for r in old['results'] if r['mode']=='async')==6
    assert sum(not r['warmup'] for r in old['results'] if r['mode']=='kafka')==6
    delta=post['createdToAnalysisDoneSeconds']-pre['createdToAnalysisDoneSeconds']
    observed='짧아졌습니다' if delta<0 else '길어졌습니다' if delta>0 else '같았습니다'
    pdfmetrics.registerFont(TTFont('KR',args.font))
    pdfmetrics.registerFontFamily('KR',normal='KR',bold='KR',italic='KR',boldItalic='KR')
    styles={
        'title':ParagraphStyle('title',fontName='KR',fontSize=23,leading=33,textColor=colors.HexColor(INK),spaceAfter=14,wordWrap='CJK'),
        'h':ParagraphStyle('h',fontName='KR',fontSize=15,leading=23,textColor=colors.HexColor(BLUE),spaceBefore=13,spaceAfter=7,wordWrap='CJK'),
        'body':ParagraphStyle('body',fontName='KR',fontSize=10.5,leading=17,textColor=colors.HexColor(INK),spaceAfter=10,wordWrap='CJK'),
        'small':ParagraphStyle('small',fontName='KR',fontSize=8.5,leading=13.5,textColor=colors.HexColor(GRAY),spaceAfter=8,wordWrap='CJK'),
        'cell':ParagraphStyle('cell',fontName='KR',fontSize=9.5,leading=15,textColor=colors.HexColor(INK),wordWrap='CJK'),
        'head':ParagraphStyle('head',fontName='KR',fontSize=9.5,leading=15,textColor=colors.white,wordWrap='CJK')}
    story=[]
    def p(s,kind='body'):return Paragraph(s.replace('−','-'),styles[kind])
    def add(s,kind='body'):story.append(p(s,kind))
    def table(rows,widths):
        t=Table([[p(str(v),'head' if i==0 else 'cell') for v in row] for i,row in enumerate(rows)],colWidths=widths,hAlign='LEFT')
        t.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),colors.HexColor(INK)),('ROWBACKGROUNDS',(0,1),(-1,-1),[colors.white,colors.HexColor(PALE)]),('VALIGN',(0,0),(-1,-1),'TOP'),('LEFTPADDING',(0,0),(-1,-1),9),('RIGHTPADDING',(0,0),(-1,-1),9),('TOPPADDING',(0,0),(-1,-1),8),('BOTTOMPADDING',(0,0),(-1,-1),8)]))
        story.extend([t,Spacer(1,12)])
    add('Spring AI와 Kafka,<br/>도입해서 정말 빨라졌을까?','title')
    add('현재 답: 전체 서비스가 빨라졌다는 증거는 없습니다. 이전 운영 측정에서는 Kafka를 쓸 때 더 오래 걸렸습니다. 좋은 수치만 골라 개선이라고 부르지 않겠습니다.')
    add('두 도구가 맡는 일','h')
    table([['도구','쉽게 말하면','속도와의 관계'],['Spring AI 2','Java 서버 코드에서 구글 AI인 Gemini를 부르는 공통 연결 도구','AI 모델 자체를 빠르게 바꾸는 도구는 아닙니다.'],['Kafka','분석할 작업을 전달하고, 중단 시 다시 받을 수 있게 하는 대기열','전달 과정이 늘어 시작이 늦어질 수 있습니다.']], [96,212,203])
    add('영상 링크 → 작업 전달 → Gemini 영상 분석 → 실제 장소 확인 → 결과 저장','small')
    add('영상의 주요 AI 추출은 Python(영상 처리 코드)이 Gemini를 직접 부릅니다. Spring AI는 Java 쪽의 일정 요청 해석·일정 생성과 추천 문구 생성에 쓰입니다. 장소 선택·검증도 현재는 Python 경로입니다. 따라서 Spring AI 호출 하나가 빨라도 영상 분석 전체가 그만큼 빨라지는 것은 아닙니다.')
    add('이전 운영 측정: Kafka 쪽이 더 오래 걸렸습니다','h')
    table([['가운데 값* / 각 6건','기존 내부 전달','Kafka 전달'],['분석 시작 전 대기',f"{med('async','queueWaitMs')/1000:.4f}초",f"{med('kafka','queueWaitMs')/1000:.4f}초"],['작업 생성 → 장소 저장 완료',f"{med('async','createdToAnalysisDoneSeconds'):.3f}초",f"{med('kafka','createdToAnalysisDoneSeconds'):.3f}초"]],[251,130,130])
    add('* 가운데 값(중앙값): 시간을 순서대로 놓았을 때 가운데에 오는 값입니다. 기존 내부 전달(async)은 서버 안에서 분석을 뒤로 실행하는 방식입니다. 두 방식 모두 비동기이며, 비동기라는 말 자체가 더 빠르다는 뜻은 아닙니다.','small')
    add('동일 영상 3개를 각 2회 측정했습니다. 전체 시간에는 Gemini 응답과 추가 검증 횟수 차이도 섞였습니다. 전체 증가분을 전부 Kafka 탓으로 확정할 수는 없습니다. 자동 일정 초안 완료 시간은 포함하지 않습니다.','small')
    story.append(PageBreak())
    add('이번 수정은 운영에서 확인했습니다','title')
    add('느린 원인을 줄이기 위해 바꾼 부분','h')
    add('① 이미 읽은 작업 정보를 다시 DB에서 읽지 않도록 했습니다. DB는 작업·장소를 저장하는 데이터베이스입니다. Kafka 분석 시작 경로에서 중복 조회 지점 두 곳을 없앴습니다.')
    add('② 장소마다 따로 저장하던 처리를 한 묶음으로 바꿨습니다. 장소 전체와 완료 표시가 함께 저장되며, 중간 실패로 일부만 남는 문제도 막습니다. 전체 SQL 요청 수나 개선률을 따로 실측한 것은 아닙니다.')
    add('같은 영상, 변경 전 1회 / 변경 후 1회','h')
    table([['실제 운영 측정','변경 전','변경 후'],['공유 요청 응답 시간',f"{pre['requestMs']/1000:.3f}초",f"{post['requestMs']/1000:.3f}초"],['분석 시작 전 대기',f"{pre['queueWaitMs']/1000:.3f}초",f"{post['queueWaitMs']/1000:.3f}초"],['작업 생성 → 장소 저장 완료',f"{pre['createdToAnalysisDoneSeconds']:.3f}초",f"{post['createdToAnalysisDoneSeconds']:.3f}초"],['저장된 장소 수',str(len(pre['savedPlaceNames'])),str(len(post['savedPlaceNames']))],['완료 여부','성공','성공']],[251,130,130])
    add(f'이번 한 쌍에서는 전체 시간이 {abs(delta):.3f}초 {observed}. 두 번 모두 Spring AI와 Kafka를 사용했습니다. 이 표는 최초 도입 전후 비교가 아니라, 현재 구성에서 중복 처리와 저장 방식을 보완한 전후 확인입니다.')
    add('속도 개선 확정으로 볼 수 없는 이유','h')
    add('각각 한 번뿐이라 AI 응답 속도의 우연한 차이를 구분할 수 없습니다. 변경 후에는 서버를 재시작했고 저장 방식 등 여러 수정이 함께 반영됐습니다. 같은 영상이어도 AI가 내놓은 후보와 추가 확인 횟수가 달라질 수 있습니다.')
    counts=lambda row:sum(c['provider']=='gemini' for c in row['apiLogRows'])
    add(f"이 작업의 영상 분석 Gemini 기록은 전 {counts(pre)}개 / 후 {counts(post)}개입니다. 자동 일정 초안은 이 집계에서 제외합니다. 기록 한 행이 실제 네트워크 요청 한 번과 항상 같지는 않습니다.",'small')
    add('운영의 남은 테스트 공유 한도 안에서 한 번씩만 실행했습니다. 더 빠른 결과가 나올 때까지 반복하지 않았습니다. 생성한 테스트 장소·초안만 비공개 백업 후 정리했고 작업·호출 기록은 남겼습니다.','small')
    story.append(PageBreak())
    add('무엇이 좋아졌고, 무엇이 남았나','title')
    table([['구분','현재 판단'],['속도','전체 속도 개선은 아직 미입증입니다. 이번 각 1회 결과도 확정 개선률로 쓰지 않습니다.'],['저장 안전성','장소 전체와 완료 표시를 함께 저장합니다. 실패 시 되돌리기와 중복 방지는 격리된 자동 검사로 확인했습니다.'],['겹치는 외부 조회','같은 가게의 영업시간 조회가 동시에 겹칠 때 한 번만 호출합니다. 자동 검사로 확인했고 실제 운영 절감량은 아직 모릅니다.'],['느린 이유 추적','Java AI의 시도 횟수와 재시도 대기를 기록합니다. 기록 실패 때문에 성공한 AI를 다시 부르던 경로도 없앴습니다.']],[110,401])
    add('정확도가 나빠진 시도는 반영하지 않았습니다','h')
    add('새 지시문(프롬프트)을 10개 영상으로 시험했지만 정답을 더 놓쳐 채택하지 않았습니다. 대신 도시 전체를 개별 장소로 저장하는 좁은 오류만 걸러냅니다. 운영 서버의 추출 단계 2사례에서 도시 이름 1개 제거·구체 장소 10개 보존을 확인했으며, 전체 정확도가 개선됐다는 뜻은 아닙니다.')
    add('일정 생성의 Spring AI 호출은 어땠나?','h')
    supplement=raw['operational-ai-supplement-20261101.json']['phases']
    java_before=supplement['before']['javaAiWindowLogs']
    java_after=supplement['after']['javaAiWindowLogs']
    assert len(java_before)==len(java_after)==1 and all(r['success'] for r in java_before+java_after)
    assert java_after[0]['attempts']==1 and java_after[0]['waitedMs']==0
    add(f"같은 운영 측정 시간대의 일정 AI 호출은 {java_before[0]['latencyMs']/1000:.3f}→{java_after[0]['latencyMs']/1000:.3f}초였습니다. 전후 모두 Spring AI이며 각1회라 도입 효과 비교는 아닙니다. 변경 후 시도1회·재시도 대기0 기록은 확인했습니다.")
    add('이 호출 기록은 개별 영상 번호와 직접 연결되지 않아 측정 시간대의 기록으로만 확인했습니다. 양쪽 자동 일정 초안은 정상 준비됐습니다.','small')
    rest=statistics.median(r['milliseconds'] for r in client['results'] if r['client']=='rest' and not r['warmup'])
    spring=statistics.median(r['milliseconds'] for r in client['results'] if r['client']=='spring-ai' and not r['warmup'])
    add(f'참고로 이전 로컬의 짧은 AI 요청 각20회에서는 직접 호출 {rest/1000:.3f}초, Spring AI {spring/1000:.3f}초가 가운데 값이었습니다. 변동 범위를 고려하면 속도 우위를 확정하지 못했습니다. 이 수치는 운영 결과가 아니며 영상 전체 시간과도 다릅니다.')
    add('실제 도입 순서: 직접 AI 호출 + 내부 전달 → 직접 AI 호출 + Kafka → Spring AI + Kafka. Spring AI만 도입한 별도 운영 단계는 없어 세 버전의 완전한 같은 조건 비교는 남아 있습니다.','small')
    add('다음 속도 판단은 오래 걸리는 AI 분석·추가 검증·DB 접근을 같은 품질 조건에서 줄였는지 운영에서 반복 확인하는 방식이 적절합니다.')
    add('2026-11-01 · 근거: docs/bench/results의 operational-before/after 및 production-queue-expanded/client-latency-expanded JSON. 상세 해설·기존 6쪽 PDF는 docs/bench에 보관합니다. PDF 재생성에는 새 API 호출이 없습니다. 운영에서는 장애를 강제로 만들지 않았습니다.','small')
    def footer(canvas,doc):
        canvas.setFont('KR',8);canvas.setFillColor(colors.HexColor(GRAY))
        canvas.drawString(42,23,'Trova  /  쉬운 성능 비교  /  2026-11-01')
        canvas.drawRightString(A4[0]-42,23,str(doc.page))
    args.output.parent.mkdir(parents=True,exist_ok=True)
    SimpleDocTemplate(str(args.output),pagesize=A4,rightMargin=42,leftMargin=42,topMargin=35,bottomMargin=40,title='Trova Spring AI와 Kafka 쉬운 성능 비교',author='Trova').build(story,onFirstPage=footer,onLaterPages=footer)
    source={'scope':'Saved production evidence, no new requests','sources':{name:hashlib.sha256(path.read_bytes()).hexdigest() for name,path in files.items()},'pdfSha256':hashlib.sha256(args.output.read_bytes()).hexdigest(),'observedDifferenceSeconds':delta,'speedImprovementProven':False}
    args.output.with_suffix('.sources.json').write_text(json.dumps(source,ensure_ascii=False,indent=2)+'\n')
    print(args.output)


if __name__=='__main__':main()
