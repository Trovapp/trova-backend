"""Render fresh alternative evidence from saved data only."""
import json
import re
from pathlib import Path
import matplotlib.pyplot as plt
from matplotlib import font_manager
from PIL import Image, ImageOps, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[1] / '2026-11-25-v11-recheck-followup'
font_manager.fontManager.addfont('C:/Windows/Fonts/malgun.ttf')
plt.rcParams['font.family'] = 'Malgun Gothic'
plt.rcParams['axes.unicode_minus'] = False
stages = []
for line in (root/'server-alternatives/journal.log').read_text(encoding='utf-8').splitlines():
    if '대안 찾기 구간별 시간:' in line:
        stages.append({k: int(v) for k, v in re.findall(r'(검색|카탈로그저장|실내태그|임베딩|혼잡도|개인화조회|추천이유)=(\d+)ms', line)})
assert len(stages) == 30
means = {k: sum(r[k] for r in stages)/30 for k in stages[0]}
counts = [int(n) for n in re.findall(r'카탈로그저장=\d+ms\((\d+)곳\)', (root/'server-alternatives/journal.log').read_text(encoding='utf-8'))]
(root/'fresh-stage-summary.json').write_text(json.dumps(dict(count=30, meansMs=means, candidateCountRange=[min(counts), max(counts)], stages=stages), ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
labels = ['11/20 (12건)', '11/25 반복 (30건)', '11/25 새 장소 (30건)']
fig, axes = plt.subplots(1, 3, figsize=(12, 3.5))
for ax, values, title in zip(axes, [[2.049, .769, 2.458], [4.080, 1.253, 3.191], [26.67, 8.27, 35.77]], ['HTTP 평균 (초)', 'p95 버킷 추정 (초)', 'SQL / 요청']):
    bars = ax.bar(range(3), values, color=['#9aafa7', '#63a798', '#d97955'])
    ax.set_xticks(range(3), labels, rotation=15, ha='right', fontsize=8)
    ax.set_title(title)
    ax.set_ylim(0, max(values)*1.22)
    ax.bar_label(bars, fmt='%.3f', fontsize=9)
fig.suptitle('장소·계정 조건이 달라 직접 개선/퇴행 판정 불가')
fig.tight_layout()
fig.savefig(root/'charts/fresh-comparison.png', dpi=170)
plt.close(fig)
fig, ax = plt.subplots(figsize=(9, 3.3))
bars = ax.bar(means.keys(), means.values(), color=['#63a798' if k != '임베딩' else '#d97955' for k in means])
ax.bar_label(bars, fmt='%.0f ms')
ax.set_ylim(0, max(means.values())*1.23)
ax.set_title('새 장소 대안 30건: 단계 로그 평균 (전체 HTTP 시간과 별도)')
fig.tight_layout()
fig.savefig(root/'charts/fresh-stages.png', dpi=170)
plt.close(fig)
canvas = Image.new('RGB', (1200, 900), 'white')
draw = ImageDraw.Draw(canvas)
font = ImageFont.truetype('C:/Windows/Fonts/malgun.ttf', 22)
for i, (path, title) in enumerate([('fresh/01/result.png', '01 서울식물원 (Google)'), ('fresh/30/result.png', '30 오설록 (Kakao)'), ('fresh-limit/daily-quota-26-preparation.png', '25건 뒤 준비 검색 일일 한도')]):
    im = Image.open(root/path).convert('RGB')
    im.thumbnail((390, 825))
    canvas.paste(im, (i*400+(400-im.width)//2, 48))
    draw.text((i*400+10, 10), title, fill='#183c35', font=font)
canvas.save(root/'charts/fresh-evidence.png')
print(json.dumps(dict(count=30, meansMs=means, candidateRange=[min(counts), max(counts)]), ensure_ascii=False))
