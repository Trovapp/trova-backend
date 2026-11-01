#!/usr/bin/env python3
"""Build the dated Korean PDF from frozen repository evidence; never calls external APIs.
Local export dependencies only: reportlab, matplotlib. Set --font to an embeddable Korean TTF.
"""
import argparse
import hashlib
import json
from pathlib import Path
import tempfile
from xml.sax.saxutils import escape

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, Image, PageBreak

ROOT = Path(__file__).resolve().parents[3]
BENCH = ROOT/'docs/bench'
INK = '#172D43'
BLUE = '#2563A6'
TEAL = '#008879'
MUTED = '#5D6D7C'
PALE = '#F0F5F9'
AMBER = '#A45E12'
SOURCE_COMMIT = '0c811cd'


def load(name):
    return json.loads((BENCH/'results'/name).read_text())


def charts(client, prod, folder):
    plt.rcParams.update({'font.family':'DejaVu Sans','font.size':9,'axes.spines.top':False,
                         'axes.spines.right':False,'axes.labelcolor':INK,'text.color':INK,
                         'axes.edgecolor':'#AAB7C2','figure.facecolor':'white'})
    fig, ax = plt.subplots(figsize=(7.2,3.0))
    for pos, (name, label, color) in enumerate([('rest','REST',BLUE),('spring-ai','Spring AI 2',TEAL)]):
        vals=[r['milliseconds']/1000 for r in client['results'] if r['client']==name and not r['warmup']]
        jitter=np.linspace(-.12,.12,len(vals))
        ax.scatter(np.full(len(vals),pos)+jitter,vals,color=color,s=28,alpha=.82,zorder=3)
        median=float(np.median(vals))
        ax.plot([pos-.24,pos+.24],[median,median],color=INK,lw=2)
        ax.annotate(f'median {median:.3f}s',(pos+.29,median),fontsize=9,va='center')
    ax.set_yscale('log');ax.set_ylim(.45,22);ax.set_xlim(-.5,1.9)
    ax.set_xticks([0,1],['REST (n=20)','Spring AI 2 (n=20)'])
    ax.set_ylabel('Client latency, seconds (log scale)')
    ax.set_yticks([.5,1,2,5,10,20],['0.5','1','2','5','10','20'])
    ax.grid(axis='y',alpha=.22);fig.tight_layout(pad=1.1)
    one=folder/'java-client-distribution.png';fig.savefig(one,dpi=220);plt.close(fig)
    fig,ax=plt.subplots(figsize=(7.2,2.8))
    rows={m:{(r['videoId'],r['repetition']):r for r in prod['results'] if r['mode']==m and not r['warmup']} for m in ['async','kafka']}
    ids=['H1bCCd1vDCA','YS4z0NqKkyw','8vmpPuFwQ94']
    for i,vid in enumerate(ids):
        for rep in [1,2]:
            vals=[rows[m][(vid,rep)]['queueWaitMs'] for m in ['async','kafka']]
            color=[BLUE,AMBER,TEAL][i]
            ax.plot([0,1],vals,color=color,alpha=.75,lw=1.2,
                    marker='o' if rep==1 else 's',linestyle='-' if rep==1 else '--',
                    markersize=4,label=f'Video {"ABC"[i]}' if rep==1 else None)
    ax.legend(loc='upper left',ncol=3,frameon=False,fontsize=8);
    ax.set_xticks([0,1],['async (n=6)','Kafka (n=6)']);ax.set_xlim(-.15,1.35)
    ax.set_ylim(0,max(r['queueWaitMs'] for r in rows['kafka'].values())*1.15)
    ax.set_ylabel('Created to analysis start, ms');ax.grid(axis='y',alpha=.22)
    fig.tight_layout(pad=1.1);two=folder/'production-queue-pairs.png';fig.savefig(two,dpi=220);plt.close(fig)
    return one,two


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--font',default='/System/Library/Fonts/Supplemental/AppleGothic.ttf')
    parser.add_argument('--output',default=str(BENCH/'reports/trova-performance-comparison-20261101.pdf'))
    args=parser.parse_args()
    pdfmetrics.registerFont(TTFont('KR',args.font))
    pdfmetrics.registerFontFamily('KR',normal='KR',bold='KR',italic='KR',boldItalic='KR')
    assert hashlib.sha256((BENCH/'results/expanded-system-analysis-20261101.json').read_bytes()).hexdigest() == '28629938c594da8810e5bf15f922ce77a752409e5db9a4594e12e08ad772b348', 'Frozen analysis differs from this dated report'
    a=load('expanded-system-analysis-20261101.json');client=load('client-latency-expanded-20261101.json')
    prod=load('production-queue-expanded-20261101.json');post=load('expanded-production-postflight-20261101.json')
    recovery=load('auxiliary-process-recovery-20261101.json')
    assert recovery['restart']['savedPlaces']==1 and recovery['concurrent']['pipelineAttempts']==1
    assert recovery['workerJvmsExited']
    assert prod['complete'] and prod['restored'] and not prod['errors']
    assert post['health']['status']=='UP'
    for name,sha in a['sourceSha256'].items():
        assert hashlib.sha256((BENCH/'results'/name).read_bytes()).hexdigest()==sha
    styles={
        'title':ParagraphStyle('title',fontName='KR',fontSize=25,leading=34,textColor=colors.HexColor(INK),spaceAfter=12,wordWrap='CJK'),
        'h1':ParagraphStyle('h1',fontName='KR',fontSize=19,leading=27,textColor=colors.HexColor(INK),spaceAfter=12,wordWrap='CJK'),
        'h2':ParagraphStyle('h2',fontName='KR',fontSize=12,leading=18,textColor=colors.HexColor(BLUE),spaceBefore=12,spaceAfter=6,wordWrap='CJK'),
        'body':ParagraphStyle('body',fontName='KR',fontSize=10,leading=16,textColor=colors.HexColor(INK),spaceAfter=8,wordWrap='CJK'),
        'small':ParagraphStyle('small',fontName='KR',fontSize=8.4,leading=13,textColor=colors.HexColor(MUTED),spaceAfter=6,wordWrap='CJK'),
        'cell':ParagraphStyle('cell',fontName='KR',fontSize=9,leading=14,textColor=colors.HexColor(INK),wordWrap='CJK'),
        'head':ParagraphStyle('head',fontName='KR',fontSize=9,leading=14,textColor=colors.white,wordWrap='CJK'),
        'code':ParagraphStyle('code',fontName='Courier',fontSize=7.1,leading=11,textColor=colors.HexColor(INK),spaceAfter=5),
    }
    story=[]
    def p(text,kind='body'):
        return Paragraph(text.replace('−','-'),styles[kind])
    def add(text,kind='body'):
        story.append(p(text,kind))
    def title(number,text):
        add(f'{number:02d}  /  {text}','h1')
    def table(rows,widths=None):
        widths=widths or [230,140,141]
        cells=[[p(str(v),'head' if i==0 else 'cell') for v in row] for i,row in enumerate(rows)]
        t=Table(cells,colWidths=widths,hAlign='LEFT',repeatRows=1)
        t.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),colors.HexColor(INK)),
            ('ROWBACKGROUNDS',(0,1),(-1,-1),[colors.white,colors.HexColor(PALE)]),
            ('VALIGN',(0,0),(-1,-1),'TOP'),('LEFTPADDING',(0,0),(-1,-1),9),
            ('RIGHTPADDING',(0,0),(-1,-1),9),('TOPPADDING',(0,0),(-1,-1),8),
            ('BOTTOMPADDING',(0,0),(-1,-1),8),('LINEBELOW',(0,-1),(-1,-1),.5,colors.HexColor('#CAD6E0'))]))
        story.append(t);story.append(Spacer(1,9))
    def callout(text):
        t=Table([[p(text)]],colWidths=[511]);t.setStyle(TableStyle([
            ('BACKGROUND',(0,0),(-1,-1),colors.HexColor(PALE)),('BOX',(0,0),(-1,-1),.5,colors.HexColor('#D9E5EF')),
            ('LEFTPADDING',(0,0),(-1,-1),12),('RIGHTPADDING',(0,0),(-1,-1),12),
            ('TOPPADDING',(0,0),(-1,-1),10),('BOTTOMPADDING',(0,0),(-1,-1),7)]))
        story.append(t);story.append(Spacer(1,9))
    def newpage():story.append(PageBreak())
    def footer(canvas,doc):
        canvas.saveState();canvas.setStrokeColor(colors.HexColor('#D5DFE7'));canvas.line(42,799,553,799)
        canvas.setFont('Helvetica-Bold',9);canvas.setFillColor(colors.HexColor(INK));canvas.drawString(42,811,'TROVA / ENGINEERING EVIDENCE')
        canvas.setFont('Helvetica',8);canvas.setFillColor(colors.HexColor(MUTED));canvas.drawRightString(553,811,'2026-11-01')
        canvas.line(42,39,553,39);canvas.setFont('KR',8)
        canvas.drawString(42,25,'관측값과 해석을 구분한 성능 비교 보고서')
        canvas.setFont('Helvetica',8);canvas.drawRightString(553,25,f'{doc.page:02d} / 06');canvas.restoreState()
    with tempfile.TemporaryDirectory(prefix='trova-pdf-charts-') as temp:
        first,second=charts(client,prod,Path(temp))
        add('기존 방식 · Spring AI 2 · Kafka<br/>성능 비교 보고서','title')
        add('2026-11-01  |  확대 실험: Java 각 20회 · 운영 큐 각 6건','small')
        callout('핵심 결과: Spring AI 2의 일반적인 속도 우위는 아직 입증되지 않았다. Kafka는 이번 낮은 부하에서 전달 지연을 추가했다. 두 도입을 합친 전체 속도 개선률은 산출할 수 없다.')
        add('무엇을 비교했나','h2')
        table([['구분','기존 방식','도입 방식'],
               ['Java AI 호출 / 로컬','REST / RestClient','Spring AI 2'],
               ['영상 시작 큐 / 운영','정상 async','Aiven Kafka'],
               ['Java 호출 중앙값 / 각 n=20','913.17ms','719.33ms'],
               ['운영 큐 대기 중앙값 / 각 n=6','234.50ms','440.00ms']],[255,128,128])
        add('실제 도입 순서','h2')
        table([['단계와 코드 기준','Java AI 호출','영상 시작 큐'],
               ['최초 / 97fc866','RestClient','async'],
               ['Kafka 도입 / 1666fee','RestClient','Kafka / async'],
               ['Spring AI 도입 / 15c1406','Spring AI 2','Kafka / async'],
               ['HTTP 수정 이후 / bd196e1 계열','Spring AI 2 · HTTP/1.1','운영 Kafka + async 폴백']],[255,128,128])
        add('중요한 범위','h2')
        add('Spring AI만 있는 중간 운영 빌드는 없었다. 세 빌드 전체를 동일 조건에서 비교한 실험도 없다. Java 클라이언트 효과와 큐 효과를 분리했다. Python 영상 추출은 Java AI 클라이언트 전환과 별도다. [S1–S3, S6]')
        add('성공 관측: Java 42/42호출, 운영 분석 15/15건. 워밍업을 제외한 요약은 각 20회·각 6건이다. 이 성공 수치로 전체 트래픽의 실패율을 일반화하지 않는다.','small')
        newpage();title(2,'Spring AI 2와 기존 REST 비교')
        add('같은 짧은 JSON 요청 · 같은 모델 식별자와 temperature · 호출 순서 교대 · 요청 사이 8초. 로컬 Mac / Java 21, 서버·DB·큐 없음. [S1, S3]','small')
        story.append(Image(str(first),width=511,height=213));story.append(Spacer(1,5))
        table([['지연 지표 / 각 n=20','REST','Spring AI 2'],
               ['중앙값','913.17ms','719.33ms'],['평균','2,798.47ms','2,423.58ms'],
               ['1사분위 / 3사분위','759.61 / 1,104.08ms','633.10 / 1,031.03ms'],
               ['최소 / 최대','602.43 / 15,385.11ms','544.86 / 15,154.69ms'],
               ['측정 성공 / 토큰 합','20/20 · 404토큰','20/20 · 404토큰']],[215,148,148])
        callout('중앙값끼리 차이: Spring AI가 193.84ms 작다.<br/>짝지은 Spring AI−REST 차이 중앙값: −136.10ms.<br/>조건부 bootstrap 중앙 95% 범위: −311.32 ~ +12.64ms. 0을 포함한다.')
        add('해석과 제한','h2')
        add('양쪽 모두 10초대 응답이 있어 평균이 중앙값보다 크다. 짝지은 차이의 중앙값과 두 중앙값의 차이는 서로 다른 통계다. seed 288·5,000회 재표집 범위는 관측 표본 조건부 설명용이다.')
        add('전송 클라이언트·연결 재사용·시간 상관·공급자 백엔드 버전을 분리하지 않았다. REST 연결 3초/읽기 20초와 Spring AI 전체 20초의 의미도 다르다. 따라서 일반적인 속도 우위, 실제 일정 생성 지연, 전체 서비스 개선률을 입증한 결과로 쓰지 않는다.','small')
        newpage();title(3,'Kafka와 정상 async 비교')
        add('동일 운영 JAR/Python · 영상 3종 × 2반복 · 경로별 n=6. 각 선은 같은 영상/반복의 짝이다. 색은 영상 A/B/C, 실선·원은 1회차, 파선·사각형은 2회차다. [S2, S3]','small')
        story.append(Image(str(second),width=511,height=199));story.append(Spacer(1,5))
        table([['운영 중앙값 / 각 n=6','정상 async','Aiven Kafka'],
               ['작업 생성 → 분석 시작','234.50ms','440.00ms'],
               ['공유 HTTP 왕복','388.14ms','636.44ms'],
               ['분석 시작 → 저장 완료','11.689초','12.060초'],
               ['작업 생성 → 저장 완료','11.919초','12.761초']],[255,128,128])
        callout('큐 대기 중앙값끼리 차이: Kafka +205.5ms.<br/>영상/반복을 맞춘 차이 중앙값: +177.5ms. 6개 짝 모두 양수였다.<br/>영상 단위 재표집 범위: +128.03 ~ +833.80ms. 3종·27조합의 설명용 범위다.')
        add('전체 분석 시간 차이를 큐 효과로 단정할 수 없는 이유','h2')
        add('측정 대상의 Gemini 논리 로그는 async 20행, Kafka 21행이었다. collect/filter/select는 각 6행으로 같지만 verify는 2행/3행이다. 알려진 시도 합은 20/21, 대기는 각각 0이다. 동적 검증 분기와 LLM 응답 변동이 전체 시간에 함께 들어갔다.')
        add('무작위 순서나 포화 부하 시험이 아니다. 현재 Spring AI 2 사용 코드에서 큐만 바꿨고, 최초 REST 서버를 운영에 재배포하지 않았다. n=6·영상 3종으로 운영 p90·처리량·전체 실패율을 일반화하지 않는다.','small')
        add('과거 로컬 전달 전용 비교(각 n=20)는 async 0.0549ms, Kafka 6.6327ms였다. DB·외부 AI·운영 TLS를 제외한 전달 비용이므로 현재 운영 지연을 대신하지 않는다. [S4]','small')
        newpage();title(4,'자원·신뢰성·비용의 비교')
        add('메모리와 산출물 크기','h2')
        table([['자료와 범위','기준 / async','도입 기록 / Kafka'],
               ['이번 운영 RSS 중앙값 / 각 n=6','320.383MiB','300.594MiB'],
               ['과거 로컬 JAR 크기 기록','최초 기준 71.2MB','Kafka 기록 83.9MB'],
               ['과거 Spring AI 작업 JAR 기록','정확한 독립 기준 미복원','95.9MB']],[255,128,128])
        add('이번 RSS는 Kafka 쪽이 작지만 과거 표본은 반대 방향이었다. GC·캐시·재시작·SDK/자동 초안 실행 이력이 달라 고정 메모리 절감으로 해석하지 않는다. 과거 JAR의 정확한 산출물 커밋/원본 자원 로그도 모두 복원되지 않아 기술별 독립 증가분을 합산할 수 없다. [S3, S6]','small')
        add('장애 경계와 얻은 동작','h2')
        table([['영역','확인한 것','보장하지 않은 것'],
               ['Spring AI 2','Java 호출 통합·계측, 앱 한 곳의 재시도. 운영 HTTP/1.1 수정 후 성공 기록','최초 REST 대비 일반 속도 향상·호출 수 절감률'],
               ['Kafka 전달','별도 JVM 재시작 후 PROCESSING 작업 재로드·재수신, 완료 저장 1개','Aiven 전원 중단 무손실·다중 JVM exactly-once'],
               ['늦은 중복 수신','단일 JVM 경계에서 실행 1회·저장 1개 통합 검사','외부 API 부분 부작용까지의 중복 없음'],
               ['Python 취소','소유 부모/관찰 자식 종료 확인 후 파일 정리, 미확인 시 보존','관찰 전 분리·새 fork·JVM SIGKILL 정리']],[80,228,203])
        add('복구 근거는 로컬 실제 Kafka·별도 Spring JVM·파일 H2·Python fixture다. 전체 HTTP/API·Supabase·실제 AI는 포함하지 않는다. 과거 Aiven 전원 중단 뒤 오프셋 초기화 기록도 있어 무조건 보존을 주장하지 않는다. [S5, S6]','small')
        add('API 사용량은 비용 지표와 구분한다','h2')
        add('로컬 42회는 파일 개별 기록이며 DB 로그에 자동 포함되지 않는다. 운영 구간 Gemini 51행 중 50행은 시도 합 50·대기 0, 초안 1행은 시도/대기 누락이다. 카카오 119로그 행도 있다. 로그 행이 wire 요청 수와 항상 같지는 않으며, 누락은 0이 아니다. 유료 전환은 없었지만 공급자 잔여 할당량·청구 금액은 직접 확인하지 않았다. [S1, S2]')
        newpage();title(5,'측정 절차와 결과 사용 기준')
        add('운영 순서 / K–A–K','h2')
        table([['K1 / Kafka','A / 정상 async','K2 / Kafka 복구'],
               ['워밍업 C 1건<br/>측정 A → B → C','워밍업 C 1건<br/>측정 C → B → A → A → B → C','워밍업 C 1건<br/>측정 C → B → A']],[170,171,170])
        add('A: H1bCCd1vDCA · B: YS4z0NqKkyw · C: 8vmpPuFwQ94. 모두 reanalyze=true, 자동 초안 설정 유지, 한 번에 한 작업만 실행했다. 각 블록 워밍업을 제외하면 모드별 6건이다. [S2]','small')
        add('지표의 시작과 끝','h2')
        table([['지표','정의'],['공유 HTTP','Mac에서 요청 시작 → HTTP 응답 수신'],
               ['큐 대기','작업 생성 → 앱 로그의 분석 진입. 실제 queuePath 대조'],
               ['생성 → 완료','DB analysis_finished_at − created_at. updated_at은 사용하지 않음'],
               ['분석 구간','생성→완료에서 로그 큐 대기를 뺀 값. 자동 초안 완료 제외'],
               ['RSS','진행 작업 0 조회 후 MainPID VmRSS 스냅샷. GC/후속 비동기 작업 미통제']],[110,401])
        add('운영 복구와 검증','h2')
        add('임시 /run 파일만 사용해 큐를 바꾸고 소유 파일 내용 확인 후 삭제했다. Kafka 복구·health UP·JAR/Python 7개 해시 유지·빈 DropInPaths·실제 WARN/ERROR 레벨 0을 확인했다. 이번 장소 114개와 초안 1개는 비공개 0600 백업 후 정리했으며 잔여 0이다. 완료 job/API 로그는 보존했다. [S2, S7]')
        add('전체 빌드: 1,010개 중 1,007개 통과, 실패/오류 0, 기존 opt-in 3개 제외. 운영 복구 도구의 오프라인 검사 5개 통과. 이것이 운영 전체 E2E나 장애 무손실 보장을 대신하지는 않는다. [S8]')
        callout('사용 가능한 표현: “동일 요청 각 20회에서 위 지연을 관측했다”, “낮은 부하의 3종 영상에서 Kafka 전달 비용을 관측했다.”<br/><br/>피해야 할 표현: “Spring AI로 전체 서비스가 21% 빨라졌다”, “Kafka로 처리량이 늘었다”, “장애에도 작업이 무조건 보존된다.”')
        newpage();title(6,'근거 파일과 재현')
        add('아래 링크는 병합된 #289의 소스 스냅샷에 고정되어 있다. PDF 생성은 저장된 결과만 읽으며 새 AI 호출이나 운영 전환을 하지 않는다.','small')
        sources=[
            ('S1','results/client-latency-expanded-20261101.json','Java 원본: 워밍업 포함 42호출'),
            ('S2','results/production-queue-expanded-20261101.json','운영 원본: 작업 15건·전환·복구·정리'),
            ('S3','results/expanded-system-analysis-20261101.json','신규 표본의 통계·짝별/영상별 차이'),
            ('S4','results/local-queue-latency-20261031.json','과거 로컬 전달 전용 비교, 각 n=20'),
            ('S5','results/auxiliary-process-recovery-20261101.json','실제 Kafka/별도 JVM 복구 fixture 결과'),
            ('S6','system-comparison-20261031.md','도입 순서·과거 자원/장애 기록의 범위'),
            ('S7','results/expanded-production-postflight-20261101.json','독립 최종 상태·인증·파일/설정 점검'),
            ('S8','results/expanded-run-manifest-20261101.json','도구/클래스 해시·빌드/검사 집계'),
        ]
        for tag,path,desc in sources:
            url=f'https://github.com/Trovapp/trova-backend/blob/{SOURCE_COMMIT}/docs/bench/{path}'
            add(f'<b>[{tag}] {desc}</b>','body')
            add(f'<link href="{url}" color="{BLUE}">{escape(path)}</link>','small')
        add('원본 무결성 / SHA-256','h2')
        for name,sha in a['sourceSha256'].items():
            add(escape(name),'small');add(sha,'code')
        add('네트워크 없이 통계 재계산','h2')
        for line in [
            'python3 docs/bench/scripts/analyze_expanded_comparison.py \\',
            '  --client docs/bench/results/client-latency-expanded-20261101.json \\',
            '  --production docs/bench/results/production-queue-expanded-20261101.json \\',
            '  --output /tmp/trova-analysis-recomputed.json']:
            add(escape(line),'code')
        add('재계산: Java seed 288·5,000회 paired bootstrap, 운영은 영상 3개 단위의 27개 복원추출 조합. 과거 n=4·배포 smoke·워밍업은 신규 요약에 합치지 않았다. 상세 조건과 한계는 expanded-system-comparison-20261101.md를 함께 확인한다.','small')
        output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True)
        doc=SimpleDocTemplate(str(output),pagesize=A4,rightMargin=42,leftMargin=42,topMargin=66,bottomMargin=53,
                              title='Trova 기존·Spring AI 2·Kafka 성능 비교',author='Trova',pageCompression=1)
        doc.build(story,onFirstPage=footer,onLaterPages=footer)
        print(output)


if __name__=='__main__':
    main()
