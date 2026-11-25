"""Prepare one memo race in the inspected memo-02/03 screen layout.

Requires A's retained conflict draft at (400,1730), B's saved memo at (290,680).
Inspect screenshots before sending the pair action. This never presses Done.
"""
from concurrent.futures import ThreadPoolExecutor
import sys
import time
from followup_ui import adb, SCRATCH, log

sample = int(sys.argv[1])
assert 4 <= sample <= 10

def prepare(device, x, y, name):
    adb(device, 'shell', 'input', 'tap', str(x), str(y))
    time.sleep(0.7)
    adb(device, 'shell', 'input', 'keycombination', '113', '29')
    adb(device, 'shell', 'input', 'text', f'{name}-memo-{sample:02d}')
    time.sleep(0.5)
    (SCRATCH / f'{device}.png').write_bytes(adb(device, 'exec-out', 'screencap', '-p'))
    log(device, 'prepared', f'{name}-memo-{sample:02d}')

with ThreadPoolExecutor(max_workers=2) as pool:
    a=pool.submit(prepare,'5584',400,1730,'A')
    b=pool.submit(prepare,'5586',290,680,'B')
    a.result()
    b.result()
