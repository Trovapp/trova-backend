"""Observe fresh conversational sessions through the emulator, at human pace.

The fixed 12-second observation is an upper bound, not response latency.
Review every resulting screenshot before continuing another group.
"""
import json
import sys
import time
import cv2
import numpy as np
from v11_ui import adb, log, ROOT, SCRATCH, stamp

device, label, first, count, message = sys.argv[1:]
first, count = int(first), int(count)
assert 1 <= count <= 5
def shot():
    return cv2.imdecode(np.frombuffer(adb(device, 'exec-out', 'screencap', '-p'), dtype=np.uint8), cv2.IMREAD_COLOR)
def tap(x,y):
    adb(device,'shell','input','tap',str(x),str(y))
    time.sleep(.9)

initial = shot()[1650:1690,100:400].copy()
for index in range(first,first+count):
    name=f'conversation-{index:02d}-{label}'
    current=shot()[1650:1690,100:400]
    if float(np.mean(cv2.absdiff(current,initial)))>3:
        raise RuntimeError('Initial chat input differs; inspect screen')
    tap(450,1670)
    adb(device,'shell','input','text',message.replace(' ','%s'))
    adb(device,'shell','input','keyevent','111')
    time.sleep(1)
    prepared=shot()
    b,g,r=map(int,prepared[1050,970])
    if not (r>150 and r>g*1.6 and r>b*1.6):
        raise RuntimeError('Send button is not enabled at expected position')
    start=stamp()
    log(device,'request-start',name+' '+message)
    t0=time.perf_counter()
    adb(device,'shell','input','tap','975','1085')
    time.sleep(12)
    frame=shot()
    elapsed=round(time.perf_counter()-t0,3)
    cv2.imwrite(str(SCRATCH/f'{name}.png'),frame)
    result=dict(sample=name,start=start,end=stamp(),observedSeconds=elapsed,method='Fixed 12s wait plus screencap; completion must be visually confirmed; upper bound only')
    with (ROOT/'ui-observations.jsonl').open('a',encoding='utf-8') as f:
        f.write(json.dumps(result,ensure_ascii=False)+'\n')
    log(device,'screenshot-observed',name+' '+str(elapsed)+'s')
    print(json.dumps(result),flush=True)
    time.sleep(5)
    tap(1005,604)
    tap(790,780)
