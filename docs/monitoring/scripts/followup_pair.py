"""Run one explicitly prepared UI action on two test emulators and observe.

No backend HTTP requests, credentials, or automatic repetition. Verify both
screens before each invocation. Host dispatch times are not server arrival times.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import threading
import time

from followup_ui import adb, ROOT, stamp

parser = argparse.ArgumentParser()
parser.add_argument('sample')
parser.add_argument('--a', nargs='+', default=['keyevent', '66'])
parser.add_argument('--b', nargs='+', default=['keyevent', '66'])
args = parser.parse_args()
assert all(c.isalnum() or c == '-' for c in args.sample)
folder = ROOT / 'observations' / args.sample
folder.mkdir(parents=True, exist_ok=False)
barrier = threading.Barrier(2)

def action(device, command):
    barrier.wait(timeout=5)
    start = stamp()
    t0 = time.perf_counter()
    adb(device, 'shell', 'input', *command)
    return dict(device=device, dispatch=start, completed=stamp(),
                elapsed=time.perf_counter()-t0, command=command)

def capture(device, phase):
    start = stamp()
    data = adb(device, 'exec-out', 'screencap', '-p')
    end = stamp()
    (folder / f'{phase}-{device}.png').write_bytes(data)
    return dict(device=device, phase=phase, captureStart=start, captureEnd=end)

with ThreadPoolExecutor(max_workers=2) as pool:
    before = list(pool.map(lambda d: capture(d, 'before'), ['5584', '5586']))
    a = pool.submit(action, '5584', args.a)
    b = pool.submit(action, '5586', args.b)
    actions = [a.result(), b.result()]
    observations = []
    base = time.perf_counter()
    for seconds in (1, 3, 6, 10):
        time.sleep(max(0, base + seconds - time.perf_counter()))
        observations.extend(pool.map(lambda d: capture(d, f'after-{seconds:02d}s'), ['5584', '5586']))
row = dict(sample=args.sample, actions=actions, before=before,
           observations=observations, httpStatus='pending server journal correlation')
(folder / 'timing.json').write_text(json.dumps(row, indent=2)+'\n', encoding='utf-8')
print(json.dumps(row))
