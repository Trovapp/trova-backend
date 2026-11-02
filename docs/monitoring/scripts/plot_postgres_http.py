"""Plot the three saved PostgreSQL HTTP comparisons offline."""
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

root = Path(__file__).resolve().parents[1] / '2026-11-02-postgres-http'
data = json.loads((root / 'summary.json').read_text())['results']
fig, axes = plt.subplots(1, 2, figsize=(11, 4), constrained_layout=True)
for ax, key, title in [(axes[0], 'medianMs', 'HTTP median (ms)'), (axes[1], 'p95Ms', 'HTTP p95 (ms)')]:
    for variant, label in [('entity', 'Before'), ('projection', 'After')]:
        rows = [r for r in data if r['places'] == 1000 and r['variant'] == variant]
        ax.plot([r['run'] for r in rows], [r[key] for r in rows], marker='o', label=label)
    ax.set(xlabel='Independent JVM run; 50 measured requests per variant', ylabel=title, xticks=[1, 2, 3], ylim=(0, None))
    ax.legend()
    ax.grid(alpha=.2)
fig.suptitle('1,000 synthetic places / PostgreSQL 17.11 / loopback HTTP (not production latency)')
fig.savefig(root / 'comparison.png', dpi=160)
