"""Change a memo only on the manually verified V11-RECHECK-531 test trip."""
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
    if float(np.mean(cv2.absdiff(shot()[170:240,300:760],title))) > 2:
        raise RuntimeError('Test trip title changed; stopping')
    name = f'memo-{index:02d}'
    adb(device,'shell','input','tap','310','1608')
    time.sleep(1)
    adb(device,'shell','input','keycombination','113','29')
    adb(device,'shell','input','text',f'V11-memo-{index:02d}')
    start = stamp()
    log(device,'request-start',name+'-keyboard-done')
    t0 = time.perf_counter()
    adb(device,'shell','input','keyevent','66')
    time.sleep(3)
    frame = shot()
    cv2.imwrite(str(SCRATCH/f'{name}.png'),frame)
    row=dict(sample=name,start=start,end=stamp(),observedSeconds=round(time.perf_counter()-t0,3),method='Keyboard done; fixed 3s wait; visually verify memo value')
    with (ROOT/'ui-observations.jsonl').open('a',encoding='utf-8') as f:
        f.write(json.dumps(row)+'\n')
    log(device,'screenshot-observed',name)
    print(json.dumps(row),flush=True)
    time.sleep(7)
