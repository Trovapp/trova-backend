"""Restore the explicitly offline test device and capture both screens.

Run after one manually prepared offline save; no automatic edit or retry.
"""
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from followup_ui import adb, ROOT, stamp, log

sample = sys.argv[1]
assert sample.startswith('offline-') and sample[8:].isdigit()
folder = ROOT / 'observations' / sample
assert folder.is_dir()
rows = []
start = stamp()
try:
    adb('5584', 'shell', 'cmd', 'connectivity', 'airplane-mode', 'disable')
finally:
    adb('5584', 'shell', 'svc', 'wifi', 'enable')
log('5584', 'network', sample + ' airplane disabled wifi restored')
def capture(device, phase):
    begin = stamp()
    (folder / f'{phase}-{device}.png').write_bytes(adb(device, 'exec-out', 'screencap', '-p'))
    return dict(device=device, phase=phase, captureStart=begin, captureEnd=stamp())
base = time.perf_counter()
with ThreadPoolExecutor(max_workers=2) as pool:
    for seconds in ((1, 3, 6, 10, 20, 40, 60) if '--extended' in sys.argv else (1, 3, 6, 10, 20)):
        time.sleep(max(0, base + seconds - time.perf_counter()))
        rows.extend(pool.map(lambda d: capture(d, f'reconnect-{seconds:02d}s'), ['5584','5586']))
(folder / 'reconnect.json').write_text(json.dumps(dict(start=start, observations=rows), indent=2), encoding='utf-8')
print(sample, 'network restored and observed')
