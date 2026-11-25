"""Plot saved v11 observations, following plot_simulator.py's offline Agg approach.

No requests are sent. HTTP comparisons are optional and must come from measured
Prometheus deltas, never from UI observations or fixed-wait screenshots.
"""
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib import font_manager
import numpy as np

font = Path('C:/Windows/Fonts/malgun.ttf')
if font.exists():
    font_manager.fontManager.addfont(str(font))
    plt.rcParams['font.family'] = font_manager.FontProperties(fname=str(font)).get_name()
plt.rcParams['axes.unicode_minus'] = False

root = Path(__file__).resolve().parents[1] / '2026-11-25-v11-recheck'
rows = [json.loads(line) for line in (root / 'ui-observations.jsonl').read_text(encoding='utf-8').splitlines()]
alternatives = [r for r in rows if r['sample'].startswith('alternative-')]
fig, axes = plt.subplots(1, 2, figsize=(13, 5), constrained_layout=True)
for day, color in [('day1', '#176458'), ('day2', '#c84724')]:
    part = [r for r in alternatives if day in r['sample']]
    x = [int(r['sample'].split('-')[1]) for r in part]
    y = [r['observedSeconds'] for r in part]
    lower = [r['observedSeconds'] - r['previousObservationSeconds'] for r in part]
    axes[0].errorbar(x, y, yerr=[lower, [0]*len(y)], fmt='o', capsize=3, color=color, label=day)
    axes[1].hist(y, bins=np.arange(1, 4.51, .25), alpha=.55, color=color, label=day)
axes[0].set(title='대안 찾기: 탭 → 버튼 복귀 관찰', xlabel='표본 번호 (첫 수동 표본 제외)', ylabel='초; 세로선은 직전 관찰과의 간격')
axes[1].set(title='화면 관찰 시간 분포', xlabel='초 (완료 관찰 상한)', ylabel='표본 수')
for ax in axes:
    ax.grid(alpha=.2)
    ax.legend()
fig.suptitle('v11 Android 사람 속도 측정: 화면 관찰과 서버 HTTP 시간을 구분')
fig.savefig(root/'ui-alternatives-distribution.png', dpi=150)
plt.close(fig)

live = [r for r in rows if r['sample'].startswith('live-') and 'firstChangedSeconds' in r]
if live:
    fig, axes = plt.subplots(1, 2, figsize=(13, 5), constrained_layout=True)
    x = [int(r['sample'].split('-')[1]) for r in live]
    first = [r['firstChangedSeconds'] for r in live]
    complete = [r['observedSeconds'] for r in live]
    axes[0].errorbar(x, first, yerr=[[r['firstChangedSeconds']-r['previousUnchangedSeconds'] for r in live], [0]*len(live)], fmt='o', label='첫 화면 변화 관찰', capsize=3)
    axes[0].plot(x, complete, 's', label='0.5초 대기 후 완성 메모 확인')
    axes[0].set(xlabel='표본 번호 (1·2번은 검출 보정으로 제외)', ylabel='A 저장 → B 화면 (초)', title='같이 고치기: 새로고침 없는 반영')
    axes[1].hist(first, bins=6, alpha=.6, label='첫 변화')
    axes[1].hist(complete, bins=6, alpha=.6, label='완성 메모 관찰 상한')
    axes[1].set(xlabel='초', ylabel='표본 수', title='첫 기준선; ADB 캡처 지연 포함')
    for ax in axes:
        ax.legend()
        ax.grid(alpha=.2)
    fig.savefig(root/'ui-live-distribution.png', dpi=150)
    plt.close(fig)

comparison = root/'server-comparison.json'
if comparison.exists():
    data = json.loads(comparison.read_text(encoding='utf-8'))
    fig, axes = plt.subplots(1, 3, figsize=(15, 5), constrained_layout=True)
    for ax, metric, label in zip(axes, ['meanSeconds', 'p95Seconds', 'sqlPerRequest'], ['HTTP 평균 (초)', 'HTTP 버킷 p95 추정 (초)', '계측 SQL / 요청']):
        available = [r for r in data if r.get('before', {}).get(metric) is not None and r.get('after', {}).get(metric) is not None]
        x = np.arange(len(available))
        ax.bar(x-.18, [r['before'][metric] for r in available], .36, label='과거 기록')
        ax.bar(x+.18, [r['after'][metric] for r in available], .36, label='2026-11-25')
        ax.set_xticks(x, [r['label'] for r in available], rotation=15)
        ax.set_title(label)
        ax.grid(axis='y', alpha=.2)
        if available:
            ax.legend()
        else:
            ax.text(.5, .5, '비교 가능한 실측 쌍 없음', ha='center', transform=ax.transAxes)
    fig.suptitle('과거 기록과 비교: 표본 수·캐시·장소 구성이 다름')
    fig.savefig(root/'server-before-after.png', dpi=150)
    plt.close(fig)
