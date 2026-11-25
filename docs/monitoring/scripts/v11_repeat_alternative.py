"""Bounded, sequential UI taps. Human pacing; never calls an HTTP API."""
import json
from pathlib import Path
import sys
import time
import cv2
import numpy as np
from v11_ui import adb, log, ROOT, SCRATCH, stamp

device, label, first, count = sys.argv[1:]
first, count = int(first), int(count)
assert 1 <= count <= 5
def screenshot():
    return cv2.imdecode(np.frombuffer(adb(device, 'exec-out', 'screencap', '-p'), dtype=np.uint8), cv2.IMREAD_COLOR)
def roi(im):
    return im[1510:1605, 350:740]
baseline = roi(screenshot()).copy()
for index in range(first, first + count):
    name = f'alternative-{index:02d}-{label}'
    start = stamp()
    log(device, 'request-start', name)
    t0 = time.perf_counter()
    adb(device, 'shell', 'input', 'tap', '540', '1560')
    busy_seen = False
    previous = None
    while time.perf_counter()-t0 < 30:
        frame = screenshot()
        changed = float(np.mean(np.max(cv2.absdiff(roi(frame), baseline), axis=2) > 35))
        elapsed = time.perf_counter()-t0
        if changed > 0.006:
            busy_seen = True
        elif elapsed > 0.7:
            break
        previous = elapsed
        time.sleep(.3)
    else:
        log(device, 'request-timeout', name)
        raise TimeoutError(name)
    result = dict(sample=name, start=start, end=stamp(), observedSeconds=round(elapsed,3), previousObservationSeconds=previous, busySeen=busy_seen, method='ADB screencap button-ready sampling; not HTTP latency')
    with (ROOT/'ui-observations.jsonl').open('a',encoding='utf-8') as f:
        f.write(json.dumps(result,ensure_ascii=False)+'\n')
    log(device, 'request-observed-end', name + ' ' + str(result['observedSeconds']) + 's busy=' + str(busy_seen))
    cv2.imwrite(str(SCRATCH/f'{name}.png'), frame)
    print(json.dumps(result),flush=True)
    time.sleep(max(6, 10-elapsed))
