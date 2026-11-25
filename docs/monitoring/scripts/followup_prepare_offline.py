"""Prepare ONE offline trial, for the manually verified retained-editor layout.

A: first library memo, previous conflict editor; B: saved first library memo.
Inspect screenshots before calling followup_pair. This intentionally leaves A
offline; followup_reconnect restores it. Never use on another screen layout.
"""
import sys
from followup_ui import adb, log, SCRATCH
import time

n = int(sys.argv[1])
assert 3 <= n <= 10
sample = f'offline-{n:02d}'
adb('5584','shell','cmd','connectivity','airplane-mode','enable')
adb('5584','shell','svc','wifi','disable')
log('5584','network',sample+' airplane enabled wifi disabled')
try:
    for device, x, y, prefix in [('5584',400,1730,'A'),('5586',300,680,'B')]:
        log(device,'tap',f'{x} {y} prepare {sample}')
        adb(device,'shell','input','tap',str(x),str(y))
        time.sleep(1)
        adb(device,'shell','input','keycombination','113','29')
        adb(device,'shell','input','text',f'{prefix}-{sample}')
        time.sleep(1)
        (SCRATCH/f'{device}.png').write_bytes(adb(device,'exec-out','screencap','-p'))
except BaseException:
    adb('5584','shell','cmd','connectivity','airplane-mode','disable')
    adb('5584','shell','svc','wifi','enable')
    raise
print(sample,'prepared; inspect both screenshots')
