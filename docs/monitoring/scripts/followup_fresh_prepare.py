"""Prepare one verified first search result in the empty V11-FRESH-533 trip.
Does not press alternative search. Inspect the resulting place name first.
"""
import time
from followup_ui import adb,log,SCRATCH
for x,y in [(970,1560),(300,590),(500,1280),(960,1390)]:
    log('5586','fresh-prepare',f'{x} {y}')
    adb('5586','shell','input','tap',str(x),str(y));time.sleep(4 if (x,y)==(970,1560) else 2.5)
(SCRATCH/'5586.png').write_bytes(adb('5586','exec-out','screencap','-p'))
