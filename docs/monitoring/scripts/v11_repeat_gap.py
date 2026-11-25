"""Reopen the shared worker test trip and inspect cached/refetched gap results.

UI entry count is not HTTP count: reconcile with server snapshots before reporting.
"""
import json
import sys
import time
import cv2
import numpy as np
from v11_ui import adb, log, stamp, ROOT, SCRATCH

first, count = map(int,sys.argv[1:])
assert 1 <= count <= 5
device='5586'
def shot():
    return cv2.imdecode(np.frombuffer(adb(device,'exec-out','screencap','-p'),np.uint8),1)
title=shot()[1230:1300,190:650].copy()
expected=cv2.imread(str(ROOT/'gap-candidates.png'))[250:300,40:500]
for index in range(first,first+count):
    if np.mean(cv2.absdiff(shot()[1230:1300,190:650],title)) > 3:
        raise RuntimeError('Test trip list position changed')
    name=f'gap-{index:02d}'
    start=stamp()
    log(device,'request-start',name+'-reopen-trip-auto-gap-query')
    t0=time.perf_counter()
    adb(device,'shell','input','tap','540','1260')
    time.sleep(5)
    adb(device,'shell','input','tap','880','2180')
    time.sleep(2)
    frame=shot()
    if np.mean(cv2.absdiff(frame[250:300,40:500],expected)) > 3:
        cv2.imwrite(str(SCRATCH/(name+'-inspect.png')),frame)
        raise RuntimeError('Gap candidates sheet not visible; inspect before continuing')
    cv2.imwrite(str(SCRATCH/(name+'.png')),frame)
    row=dict(sample=name,start=start,end=stamp(),observedSeconds=round(time.perf_counter()-t0,3),method='Reopen trip, fixed 5s wait, open gap sheet, fixed 2s wait; not HTTP response time')
    with (ROOT/'ui-observations.jsonl').open('a',encoding='utf-8') as f:
        f.write(json.dumps(row)+'\n')
    log(device,'candidates-observed',name)
    print(json.dumps(row),flush=True)
    time.sleep(3)
    adb(device,'shell','input','swipe','540','170','540','1900','500')
    time.sleep(1.5)
    adb(device,'shell','input','tap','75','210')
    time.sleep(2)
