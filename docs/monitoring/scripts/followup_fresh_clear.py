"""Remove the single tested place from V11-FRESH-533 after checking its result.
Only for the inspected one-place layout; leaves bookmarks unchanged.
"""
import time
from followup_ui import adb,log,SCRATCH
for x,y in [(1000,1230),(1000,1225),(200,2180),(890,1350)]:
    log('5586','fresh-clear',f'{x} {y}')
    adb('5586','shell','input','tap',str(x),str(y));time.sleep(2)
(SCRATCH/'5586.png').write_bytes(adb('5586','exec-out','screencap','-p'))
