"""ADB UI observation helper. No API credentials or direct backend requests."""
import datetime
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = str(Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/platform-tools/adb.exe')
ROOT = Path(__file__).resolve().parents[1] / '2026-11-25-v11-recheck'
SCRATCH = Path(os.environ.get('ORCA_CHAT_SCRATCH_DIR', str(ROOT / 'scratch')))
KST = datetime.timezone(datetime.timedelta(hours=9))

def stamp():
    return datetime.datetime.now(KST).isoformat(timespec='milliseconds')

def adb(device, *args):
    return subprocess.check_output([ADB, '-s', 'emulator-' + device, *args], timeout=40)

def log(device, kind, detail):
    ROOT.mkdir(parents=True, exist_ok=True)
    with (ROOT / 'request-times.txt').open('a', encoding='utf-8') as f:
        f.write(f'{stamp()}\t{device}\t{kind}\t{detail}\n')

def nodes(device):
    path = '/sdcard/v11-window.xml'
    adb(device, 'shell', 'rm', '-f', path)
    adb(device, 'shell', 'uiautomator', 'dump', path)
    return list(ET.fromstring(adb(device, 'shell', 'cat', path)).iter('node'))

def main():
    device, action, *args = sys.argv[1:]
    SCRATCH.mkdir(parents=True, exist_ok=True)
    if action == 'shot':
        p = SCRATCH / (device + '.png')
        p.write_bytes(adb(device, 'exec-out', 'screencap', '-p'))
        print(p)
    elif action == 'dump':
        print(stamp())
        for n in nodes(device):
            if n.get('text') or n.get('content-desc'):
                print(json.dumps({k: n.get(k) for k in ['text', 'content-desc', 'bounds', 'clickable']}, ensure_ascii=False))
    elif action == 'tap':
        log(device, 'tap', ' '.join(args))
        adb(device, 'shell', 'input', 'tap', args[0], args[1])
        time.sleep(0.7)
    elif action == 'click':
        matches = [n for n in nodes(device) if n.get('clickable') == 'true' and args[0] in (n.get('text', '').replace('\u2060', ''), n.get('content-desc', ''))]
        if len(matches) != 1:
            raise ValueError(f'Expected one clickable {args[0]}, got {len(matches)}')
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', matches[0].get('bounds')))
        log(device, 'click', ' '.join(args))
        adb(device, 'shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
        time.sleep(0.7)
    elif action == 'text':
        adb(device, 'shell', 'input', 'text', args[0])
    elif action == 'back':
        adb(device, 'shell', 'input', 'keyevent', '4')
    elif action == 'mark':
        log(device, args[0], ' '.join(args[1:]))
        print(stamp(), ' '.join(args))
    elif action == 'wait':
        deadline = time.monotonic() + float(args[1])
        while time.monotonic() < deadline:
            try:
                text = ' '.join(n.get('text', '') + n.get('content-desc', '') for n in nodes(device)).replace('\u2060', '')
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ET.ParseError):
                time.sleep(2)
                continue
            if args[0] in text:
                log(device, 'observed', args[0])
                print(stamp(), args[0])
                return
            time.sleep(2)
        log(device, 'timeout', args[0])
        raise TimeoutError(args[0])
    else:
        raise ValueError(action)

if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main()
