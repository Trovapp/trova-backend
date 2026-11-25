"""Reopen the worker-created trip at human pace; review each group of at most 5."""
import sys
import time
import json
import cv2
import numpy as np
from v11_ui import adb, log, stamp, ROOT, SCRATCH

device, first, count = sys.argv[1:]
first, count = int(first), int(count)
assert 1 <= count <= 5

def shot():
    return cv2.imdecode(np.frombuffer(adb(device, 'exec-out', 'screencap', '-p'), dtype=np.uint8), cv2.IMREAD_COLOR)

baseline = shot()[1365:1430,60:520].copy()
for index in range(first, first+count):
    current = shot()[1365:1430,60:520]
    if float(np.mean(cv2.absdiff(current, baseline))) > 3:
        raise RuntimeError('Trip title position changed; inspect before tapping')
    name = f'detail-{index:02d}-multiday'
    start = stamp()
    log(device,'request-start',name)
    t0 = time.perf_counter()
    adb(device,'shell','input','tap','500','1400')
    time.sleep(4)
    frame = shot()
    seconds = round(time.perf_counter()-t0,3)
    cv2.imwrite(str(SCRATCH/f'{name}.png'),frame)
    b,g,r = map(int,frame[600,200])
    if not(g > r and g > 45 and r < 60):
        raise RuntimeError('Expected day-one tab not visible; inspect result')
    row = dict(sample=name,start=start,end=stamp(),observedSeconds=seconds,method='Fixed 4s wait plus screenshot; visual completion upper bound only')
    with (ROOT/'ui-observations.jsonl').open('a',encoding='utf-8') as f:
        f.write(json.dumps(row)+'\n')
    log(device,'screenshot-observed',name)
    print(json.dumps(row),flush=True)
    time.sleep(4)
    adb(device,'shell','input','tap','74','210')
    time.sleep(1.2)
    adb(device,'shell','input','tap','74','210')
    time.sleep(2)
