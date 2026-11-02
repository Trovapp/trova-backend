"""Render collected data; requires matplotlib. Usage: python plot.py OUTPUT_DIR"""
import json
from pathlib import Path
import sys
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

root = Path(sys.argv[1])
runs = json.loads((root/'runs.json').read_text())
fig, axes = plt.subplots(3, 2, figsize=(13,11), layout='constrained')
labels = [r['label'] for r in runs]
axes[0,0].plot(labels,[r['p95_ms'] for r in runs],'o-',label='p95')
axes[0,0].plot(labels,[r['median_ms'] for r in runs],'o-',label='median')
axes[0,0].axhline(1000,color='red',ls='--',label='experiment target')
axes[0,0].set_ylabel('HTTP latency (ms)'); axes[0,0].legend()
axes[0,0].tick_params(axis='x',rotation=45)
start = runs[0]['start']
for ax,name,title in [(axes[0,1],'http_rate','Throughput (requests/s, 20s rate)'),(axes[1,0],'cpu','JVM process CPU usage'),(axes[1,1],'connections_pending','DB connection waiters')]:
    data=json.loads((root/('prom-'+name+'.json')).read_text())['data']['result']
    for series in data:
        vals=series['values']; ax.plot([(float(t)-start)/60 for t,_ in vals],[float(v) for _,v in vals])
    for r in runs:
        ax.axvline((r['start']-start)/60,color='grey',alpha=.3)
        ax.text((r['start']-start)/60,1.01,str(r['vus']),transform=ax.get_xaxis_transform(),fontsize=8)
    ax.set_title(title, pad=25); ax.set_xlabel('Minutes from first run (top labels: VUs)')
for series_index, service in enumerate(['app','db']):
    values=[]
    for r in runs:
        before=dict(line.split() for line in r['cpu_before'][service].splitlines())
        after=dict(line.split() for line in r['cpu_after'][service].splitlines())
        values.append((int(after['usage_usec'])-int(before['usage_usec']))/1e6/(r['end']-r['start'])*100)
    axes[2,0].plot(labels,values,'o-',label=service)
axes[2,0].set_ylabel('CPU % of one core (whole run)'); axes[2,0].legend()
axes[2,0].tick_params(axis='x',rotation=45)
series=json.loads((root/'prom-connection_acquire.json').read_text())['data']['result']
for data in series:
    axes[2,1].plot([(float(t)-start)/60 for t,_ in data['values']],[float(v)*1000 for _,v in data['values']])
axes[2,1].set_title('Mean DB connection acquisition (ms, 20s rate)')
axes[2,1].set_xlabel('Minutes from first run')
fig.suptitle('Isolated capacity test: 1 app CPU, 1000 places/user, 1s think time')
fig.savefig(root/'capacity.png',dpi=160)
