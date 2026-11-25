"""Swap the first two places of the verified worker-created trip, at human pace."""
import json
import sys
import time
import cv2
import numpy as np
from v11_ui import adb, log, stamp, ROOT, SCRATCH

device, first, count = sys.argv[1:]
first, count = int(first), int(count)
assert 1 <= count <= 5

def shot():
    return cv2.imdecode(np.frombuffer(adb(device,'exec-out','screencap','-p'),dtype=np.uint8),cv2.IMREAD_COLOR)

title = shot()[170:240,300:760].copy()
for index in range(first,first+count):
    before = shot()
    if float(np.mean(cv2.absdiff(before[170:240,300:760],title))) > 2:
        raise RuntimeError('Test trip title changed; stopping')
    adb(device,'shell','input','tap','1005','1500')
    time.sleep(2)
    name = f'order-{index:02d}'
    start = stamp()
    log(device,'request-start',name+'-first-place-down')
    t0 = time.perf_counter()
    adb(device,'shell','input','tap','310','1705')
    time.sleep(3)
    frame = shot()
    cv2.imwrite(str(SCRATCH/f'{name}.png'),frame)
    if float(np.mean(cv2.absdiff(before[1360:1410,140:650],frame[1360:1410,140:650]))) < 2:
        raise RuntimeError('First place did not change; inspect result')
    row=dict(sample=name,start=start,end=stamp(),observedSeconds=round(time.perf_counter()-t0,3),method='First-place down action; fixed 3s wait; verify names and time recalculation')
    with (ROOT/'ui-observations.jsonl').open('a',encoding='utf-8') as f:
        f.write(json.dumps(row)+'\n')
    log(device,'screenshot-observed',name)
    print(json.dumps(row),flush=True)
    time.sleep(6)
