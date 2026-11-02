"""Plot saved observations only; no network, credentials, or new load."""
import datetime
import json
from pathlib import Path
from zoneinfo import ZoneInfo
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.dates as mdates

root = Path(__file__).resolve().parents[1]
data = json.loads((root / '2026-11-02-read-smoke-results.json').read_text())
zone = ZoneInfo('Asia/Seoul')
def stamp(value):
    return datetime.datetime.fromtimestamp(value, zone)

for name in ['health-v2', 'places-v2']:
    trial = data['trials'][name]
    run = trial['run']
    source = data['prometheus'][name]['series']
    start, end = stamp(run['start']), stamp(run['end'])
    fig, axes = plt.subplots(3, 2, figsize=(13, 10), constrained_layout=True)
    ax = axes.flat
    samples = trial['requestSamples']
    times = [datetime.datetime.fromisoformat(p['time'].replace('Z', '+00:00')).astimezone(zone) for p in samples]
    ax[0].plot(times, [p['durationMs'] for p in samples], '.-', label='k6 request duration')
    ax[0].set(title='1. Client response time (each request)', ylabel='ms')

    def draw(axis, key, factor=1, legend=None):
        for series in source[key]['result']:
            xs = [stamp(t) for t, _ in series['values']]
            ys = [float(v)*factor for _, v in series['values']]
            label = legend or series['metric'].get('uri', key)
            axis.step(xs, ys, where='post', label=label)

    draw(ax[1], 'requestsPerSecond')
    ax[1].set(title='2. Server request rate (1-minute window)', ylabel='requests/s')
    draw(ax[2], 'serverAverageSeconds', 1000)
    ax[2].set(title='3. Server mean response time (1-minute window)', ylabel='ms')
    draw(ax[3], 'cpu', 100, 'Java process')
    ax[3].set(title='4. Sampled Java CPU usage', ylabel='%')
    draw(ax[4], 'heapBytes', 1/(1024**2), 'JVM heap used')
    ax[4].plot([stamp(s['at']) for s in run['resourceSamples']],
               [s['availableKiB']/1024 for s in run['resourceSamples']], '.-', label='Host memory available')
    ax[4].set(title='5. Memory (different scopes)', ylabel='MiB')
    draw(ax[5], 'dbActive', legend='DB active')
    draw(ax[5], 'dbPending', legend='DB pending')
    ax[5].set(title='6. Sampled DB connections', ylabel='connections', ylim=(0, 1), yticks=[0, 1])
    for axis in axes.flat:
        axis.axvspan(start, end, alpha=.12, color='tab:blue', label='_test window')
        axis.set_xlim(stamp(run['start']-30), stamp(run['end']+15))
        axis.set_ylim(bottom=0)
        axis.xaxis.set_major_formatter(mdates.DateFormatter('%H:%M:%S', tz=zone))
        axis.tick_params(axis='x', labelrotation=20)
        axis.grid(alpha=.25)
        axis.legend(fontsize=8, loc='upper left')
        axis.set_xlabel('KST (UTC+9) — blue band = test')
    count = trial['summary']['metrics']['http_reqs']['count']
    status = 'COMPLETED' if run['exitCode']==0 else 'STOPPED: resource guard'
    fig.suptitle(f'{name}: {count} requests, 1 VU — {status}\nPrometheus scrape: 15s; chart query: 5s (repeated samples, not finer measurement)', fontsize=13)
    fig.savefig(root / f'2026-11-02-{name}.png', dpi=150)
    plt.close(fig)
