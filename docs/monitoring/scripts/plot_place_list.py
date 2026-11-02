"""Plot saved paired measurements without calling any server."""
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

root = Path(__file__).resolve().parents[1]
data = json.loads((root / '2026-11-02-place-list-benchmark/summary.json').read_text())
fig, axes = plt.subplots(1, 2, figsize=(11, 4), constrained_layout=True)
for ax, key, unit in [(axes[0], 'total_ns', 'Query + mapping median (ms)'),
                      (axes[1], 'allocated_bytes', 'Thread allocation median (MB)')]:
    for variant, label in [('entity', 'Before: entity query'), ('projection', 'After: scalar query')]:
        rows = [r for r in data['results'] if r['places'] == 1000 and r['variant'] == variant]
        ax.plot([r['run'] for r in rows], [r[key]['median']/1e6 for r in rows], marker='o', label=label)
    ax.set(xlabel='Independent JVM run (50 samples per variant)', ylabel=unit, xticks=[1, 2, 3], ylim=(0, None))
    ax.grid(alpha=.2)
    ax.legend()
fig.suptitle('1,000 synthetic places / H2 local comparison — not production API latency')
fig.savefig(root / '2026-11-02-place-list-benchmark/comparison.png', dpi=160)
