"""One prepared alternative lookup, once per numbered fresh place. No retry."""
import json
import sys
import time
from pathlib import Path
import cv2
import numpy as np
from followup_ui import adb, ROOT, stamp, log
sys.stdout.reconfigure(encoding='utf-8')

n=int(sys.argv[1]);name=sys.argv[2]
device=sys.argv[3] if len(sys.argv)>3 else "5586"
assert device in ("5584", "5586")
assert 1<=n<=30
folder=ROOT/'fresh'/f'{n:02d}'
folder.mkdir(parents=True,exist_ok=False)
def shot(label):
    data=adb(device,'exec-out','screencap','-p')
    (folder/f'{label}.png').write_bytes(data)
    return cv2.imdecode(np.frombuffer(data,dtype=np.uint8),cv2.IMREAD_COLOR)
base=shot('before')[1510:1605,350:740].copy()
start=stamp();t0=time.perf_counter();log(device,'fresh-start',f'{n:02d} {name}')
adb(device,'shell','input','tap','540','1560')
busy=False;observations=[];ready=False
while time.perf_counter()-t0<60:
    frame=shot(f'frame-{len(observations):03d}')
    elapsed=time.perf_counter()-t0
    changed=float(np.mean(np.max(cv2.absdiff(frame[1510:1605,350:740],base),axis=2)>35))
    observations.append(dict(seconds=round(elapsed,3),changed=round(changed,5)))
    if changed>.006:busy=True
    elif elapsed>1:
        ready=True;break
    time.sleep(.3)
end=stamp();time.sleep(3);shot('result')
row=dict(sample=n,place=name,device=device,start=start,end=end,buttonReadyObserved=ready,busySeen=busy,observedSeconds=round(elapsed,3),observations=observations,method='ADB screenshot button-ready observation; HTTP timing from server separately')
(folder/'timing.json').write_text(json.dumps(row,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
log(device,'fresh-end',f'{n:02d} ready={ready} observed={elapsed:.3f}s')
print(json.dumps(row,ensure_ascii=False))
