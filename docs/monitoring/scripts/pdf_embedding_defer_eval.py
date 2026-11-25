#!/usr/bin/env python3
"""저장된 T45 지표에서 한국어 PNG·2쪽 PDF 작성. 네트워크/DB 접근 없음."""
import json,os
from pathlib import Path
os.environ.setdefault('MPLCONFIGDIR','/private/tmp/t45-matplotlib')
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib import font_manager
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.cidfonts import UnicodeCIDFont
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.pagesizes import A4
from reportlab.platypus import SimpleDocTemplate,Paragraph,Spacer,Table,TableStyle,Image,PageBreak
BASE=Path(__file__).resolve().parents[1]/'2026-11-25-embedding-defer-eval'
m=json.loads((BASE/'data/metrics.json').read_text())
def pick(v,mask='mixed',scope='complete23'):return next(s for s in m['summaries'] if s['variant']==v and s['mask']==mask and s['scope']==scope)
font=Path(os.environ.get('T45_FONT','/System/Library/Fonts/Supplemental/AppleGothic.ttf'))
if not font.exists():raise SystemExit('T45_FONT 환경변수에 한국어 TTF/OTF 폰트 경로를 지정하세요.')
if font.exists():
    font_manager.fontManager.addfont(str(font));plt.rcParams['font.family']=font_manager.FontProperties(fname=str(font)).get_name()
plt.rcParams['axes.unicode_minus']=False
fig,ax=plt.subplots(1,3,figsize=(15,4.9),layout='constrained')
labels=['전체 개인화 제외','기존 벡터만 적용','카테고리 대체']
x=list(range(3));vals=[pick(v) for v in ('B1','B2','B3')]
ax[0].bar([i-.18 for i in x],[s['overlap5']*100 for s in vals],.36,label='상위 5개 겹침',color='#287a7b')
ax[0].bar([i+.18 for i in x],[s['top1']*100 for s in vals],.36,label='1위 일치율',color='#dc8438')
ax[0].set(xticks=x,xticklabels=labels,ylim=(0,110),ylabel='비율 (%)',title='첫 응답의 순위 일치 · 23요청')
ax[0].tick_params(axis='x',labelrotation=15);ax[0].legend(loc='upper right',fontsize=9)
for i,s in enumerate(vals):ax[0].text(i+.18,s['top1']*100+1,f'{s["top1"]*100:.1f}',ha='center',fontsize=9)
ax[1].bar(['현재 실측','지연 후 추정'],[2.458,2.458-(m['stages']['embedding_ms']['mean']-33)/1000],color=['#64768b','#287a7b'],width=.55)
ax[1].set(ylim=(0,2.9),ylabel='HTTP 평균 (초)',title='별도 운영 30요청의 시간 추정')
ax[1].text(0,2.53,'2.458초',ha='center');ax[1].text(1,1.18,'약 1.09초',ha='center');ax[1].text(.5,.45,'약 1.37초 감소 예상\n비동기 실측 아님',ha='center',fontsize=11)
ax[2].bar(['입력 원본','이름 정확 일치','주 비교 표본'],[30,27,23],color=['#64768b','#89aaa5','#287a7b'],width=.55)
ax[2].set(ylim=(0,35),ylabel='요청 수',title='현재 카탈로그 재구성의 한계')
for i,n in enumerate([30,27,23]):ax[2].text(i,n+.6,str(n),ha='center')
fig.suptitle('새 장소 임베딩 지연 비교 · 카탈로그 반사실 실험',fontsize=17)
fig.savefig(BASE/'comparison.png',dpi=160);plt.close(fig)

pdfmetrics.registerFont(UnicodeCIDFont('HYSMyeongJo-Medium'))
style=ParagraphStyle('body',fontName='HYSMyeongJo-Medium',fontSize=10,leading=15,spaceAfter=7)
heading=ParagraphStyle('heading',parent=style,fontSize=17,leading=23,spaceAfter=12)
small=ParagraphStyle('small',parent=style,fontSize=8,leading=12)
blocks=[]
def para(text,h=False):blocks.append(Paragraph(text,heading if h else style))
def table(rows,widths):
    t=Table([[Paragraph(str(c),small) for c in row] for row in rows],colWidths=widths,repeatRows=1)
    t.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),colors.HexColor('#e6efee')),('VALIGN',(0,0),(-1,-1),'TOP'),('GRID',(0,0),(-1,-1),.35,colors.HexColor('#ccd4d4')),('TOPPADDING',(0,0),(-1,-1),6),('BOTTOMPADDING',(0,0),(-1,-1),6)]));blocks.extend([t,Spacer(1,12)])
para('새 장소 임베딩을 응답 뒤로 미루면',True)
para('판단: 운영 적용은 보류. 후속 검증 후보는 첫 요청의 전체 개인화를 잠시 제외하는 B1. 약 1.37~1.40초를 줄일 여지가 있으나, 실제 첫 응답 정확도와 사용자 만족은 이번 자료로 확정할 수 없다.')
para('실제 당시 후보·여행이 소실돼 현재 카탈로그로 재구성했다. 두 테스트 계정, 30원본·373후보 중 이름이 정확하고 현재 벡터가 모두 있는 23요청·292후보가 주 비교다. 일반 사용자 데이터와 신규 외부 API 호출은 없다.')
rows=[['변형 · mixed 가정','상위 5 겹침','상위 10 겹침','Spearman','1위 일치']]
for v in ('B1','B2','B3'):
 s=pick(v);rows.append([v+' '+dict(B1='전체 개인화 제외',B2='기존 벡터만',B3='카테고리 대체')[v],f'{s["overlap5"]*100:.1f}%',f'{s["overlap10"]*100:.1f}%',f'{s["spearman"]:.4f}',f'{s["top1"]*100:.1f}%'])
table(rows,[160,85,85,80,80])
blocks.append(Image(str(BASE/'comparison.png'),width=490,height=160))
para('겹침은 요청별 교집합/min(k,후보 수)의 평균. B1은 상위 5 집합과 1위가 유지돼도 전체 순서가 7/23건 바뀌었고, 취향 문구 44개가 모두 사라졌다. mixed는 신규로 가정한 후보가 291/292개라 기존·신규가 많이 섞인 상황을 대표하지 않는다.')
blocks.append(PageBreak())
para('사례와 적용 전 필요한 검증',True)
para('인천 차이나타운 R02: B2는 기존 벡터가 있다는 이유로 C011(평점 3.7, 리뷰 702, 거리 862m)을 11위에서 1위로 올렸다. A의 1위 C001은 평점 4.0·리뷰 8,173이었다. 이는 관련성 오답의 증명은 아니지만 기존 후보에만 최대 약 60% 부스트가 남는 편향을 보여 준다.')
para('카테고리 대체 B3는 1위가 6/23건 바뀌었다. 수원 R03에서는 평점 4.6 사찰이 상위 5에서 빠지고 평점 4.2 관광명소가 들어왔다. 카테고리 빈도는 코사인 유사도와 척도가 달라 보정 없이 대체하기 어렵다.')
para('불확실성: 화면 OCR에서 읽은 33개 후보 중 재구성 집합에 25개(75.8%)가 있었다. 주 표본에서는 17/23개(73.9%). 이름이 부분일치인 3요청, 현재 벡터가 없는 6후보가 속한 4요청을 주 비교에서 제외했다. R27은 이름이 같아도 교통시설 분류여서 원본 의미가 의심된다. 실제 Google 전체 유형·후보 입력 순서·원래 누락마스크는 복원되지 않았다.')
para('민감도: 이름 정확일치 27요청 전체에서는 B1/B2/B3의 1위 일치율이 각각 92.6%/88.9%/70.4%다. 이 표에는 벡터 미완성 A가 섞여 주 결론으로 쓰지 않는다. 전부 신규로 보는 all-new에서는 B2가 B1과 같아지고, B3의 1위 일치율은 73.9%다.')
para('후속 설계(미구현): 장소 ID·모델 버전·텍스트 해시로 중복 작업을 막고, 커밋 뒤 제한된 대기열에서 묶음 임베딩한다. 재시작 복구, 재시도 지수 백오프·지터·상한, 429 Retry-After, 큐 크기 상한이 필요하다. 분당 15회는 이번 과제의 예산 가정이며 모델/키 실제 한도와 배치 계수 단위를 확인하고 API 전역 예산을 공유해야 한다.')
para('다음 요청이 현재 방식과 같아지는 것은 비동기 생성·저장이 완료되고 같은 모델·텍스트·사용자 신호가 유지될 때만이다. 완료 전 재요청·실패·한도 대기에서는 차이가 계속된다. 후보/점수/누락마스크를 고정한 그림자 비교와 실제 응답시간 측정으로 적용을 다시 판단한다.')
para('검증: 실제 AlternativeFinderService·PersonalizationService 재생, 합성 픽스처 및 기존 대상 단위테스트 통과. 운영 서비스/설정 변경·머지·배포 없음. 상세 원자료·산식·재현 절차는 같은 폴더 README와 data/ 참조.')
SimpleDocTemplate(str(BASE/'summary.pdf'),pagesize=A4,rightMargin=42,leftMargin=42,topMargin=36,bottomMargin=34,title='새 장소 임베딩 지연 비교',author='Trova').build(blocks)
print('comparison.png, summary.pdf 생성')
