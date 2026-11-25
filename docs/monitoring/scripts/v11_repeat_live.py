"""Observe B's memo pixels after A edits the explicitly verified test trip."""
import json
import sys
import time
import cv2
import numpy as np
from v11_ui import adb, log, stamp, ROOT, SCRATCH

first, count = map(int, sys.argv[1:])
assert 1 <= count <= 5

def shot(device):
    return cv2.imdecode(np.frombuffer(adb(device, 'exec-out', 'screencap', '-p'), np.uint8), 1)

title = shot('5584')[170:240,300:760].copy()
for index in range(first, first+count):
    if np.mean(cv2.absdiff(shot('5584')[170:240,300:760], title)) > 2:
        raise RuntimeError('Test trip header changed')
    before = shot('5586')[1800:1860,170:440].copy()
    adb('5584','shell','input','tap','310','1708')
    time.sleep(1)
    adb('5584','shell','input','keycombination','113','29')
    adb('5584','shell','input','text',f'V11-live-{index:02d}')
    start = stamp()
    log('5584','request-start',f'live-{index:02d}-keyboard-done')
    t0 = time.perf_counter()
    adb('5584','shell','input','keyevent','66')
    previous = 0.0
    while True:
        frame = shot('5586')[1800:1860,170:440]
        elapsed = time.perf_counter()-t0
        difference = int(np.count_nonzero(np.max(cv2.absdiff(frame,before),axis=2) > 25))
        if difference > 20:
            break
        if elapsed > 15:
            raise RuntimeError('No B memo change within 15 seconds')
        previous = elapsed
        time.sleep(0.2)
    first_changed = elapsed
    time.sleep(0.5)
    frame = shot('5586')[1800:1860,170:440]
    elapsed = time.perf_counter()-t0
    cv2.imwrite(str(SCRATCH/f'live-{index:02d}-memo.png'),frame)
    row=dict(sample=f'live-{index:02d}',start=start,end=stamp(),previousUnchangedSeconds=round(previous,3),observedSeconds=round(elapsed,3),firstChangedSeconds=round(first_changed,3),method='A keyboard done to B changed memo pixels; ADB capture interval; no B refresh')
    with (ROOT/'ui-observations.jsonl').open('a',encoding='utf-8') as f:
        f.write(json.dumps(row)+'\n')
    log('5586','memo-change-observed',f'live-{index:02d}')
    print(json.dumps(row),flush=True)
    time.sleep(8)
