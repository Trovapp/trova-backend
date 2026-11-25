"""Kakao test device: clear one measured place and search next; no alternative call."""
import subprocess,sys,time
from followup_ui import adb,log,SCRATCH
sys.stdout.reconfigure(encoding='utf-8')
for x,y in [(1005,1245),(1005,1325),(180,2250),(895,1375)]:
    log('5584','fresh-clear',f'{x} {y}');adb('5584','shell','input','tap',str(x),str(y));time.sleep(2)
subprocess.run(['powershell','-NoProfile','-Command',"Set-Clipboard -Value " + "'" + sys.argv[1].replace("'", "''") + "'"],check=True)
adb('5584','shell','input','tap','400','1455');time.sleep(1)
adb('5584','shell','input','keycombination','113','29')
adb('5584','shell','input','keyevent','279');time.sleep(1)
adb('5584','shell','input','keyevent','66');log('5584','fresh-search',sys.argv[1]);time.sleep(4)
(SCRATCH/'5584.png').write_bytes(adb('5584','exec-out','screencap','-p'))
