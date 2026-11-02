"""Render the saved simulator observation; no network or credentials required."""
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

root = Path(__file__).resolve().parents[1]
r = json.loads((root / '2026-11-02-simulator-results.json').read_text())
fig, axes = plt.subplots(3, 2, figsize=(14, 10), constrained_layout=True)
rows = r['requests']
axes[0, 0].barh([x['uri'].replace('/api/', '') + ' ' + x['status'] for x in rows],
               [x['serverMeanMs'] for x in rows])
axes[0, 0].set(title='Server mean per endpoint (small samples)', xlabel='Milliseconds; excludes network and rendering')
for ax, name, scale, title in [(axes[0, 1], 'requests', 1, 'API requests/sec: rolling 1 minute'),
                              (axes[1, 0], 'cpu', 100, 'Java CPU (%)'),
                              (axes[1, 1], 'heap', 1 / 1048576, 'Java heap (MiB)'),
                              (axes[2, 0], 'dbPending', 1, 'DB connections waiting (sampled)')]:
    series_list = r['series'][name]['result']
    if name == 'requests':
        totals = {}
        for series in series_list:
            for t, value in series['values']:
                totals[t] = totals.get(t, 0) + float(value)
        series_list = [{'values': sorted(totals.items())}]
    for series in series_list:
        ax.plot([(v[0]-r['start']) for v in series['values']],
                [float(v[1])*scale for v in series['values']])
    ax.axvline(r['end']-r['start'], color='gray', linestyle='--')
    ax.set(title=title, xlabel='Seconds from 10:27:20 KST; dashed = actions finished')
    ax.grid(alpha=.2)
samples = r['resources']
axes[2, 1].plot([s['at']-r['start'] for s in samples],
                [s['resource']['memorySomeAvg10'] for s in samples], color='darkorange')
axes[2, 1].axvline(r['end']-r['start'], color='gray', linestyle='--')
axes[2, 1].set(title='Host memory PSI some avg10 (%)', xlabel='Seconds from start; SSH samples ~6 seconds')
fig.suptitle('Trova iOS simulator observation — 2026-11-02 (server-wide metrics)', fontsize=15)
fig.savefig(root / '2026-11-02-simulator.png', dpi=150)
