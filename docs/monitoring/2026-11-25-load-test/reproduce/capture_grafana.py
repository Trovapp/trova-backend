"""Capture disposable local Grafana with a separate headless Chrome profile; no credentials."""
import base64,json,subprocess,time,tempfile,urllib.request,urllib.parse
from pathlib import Path
import websocket
HERE=Path(__file__).resolve().parent;OUT=HERE.parent
rows=json.loads((OUT/'raw/runs.json').read_text());start=int(min(r['start'] for r in rows)*1000);end=int(max(r['end'] for r in rows)*1000)
url=f'http://127.0.0.1:13334/d/isolated534/isolated?orgId=1&from={start}&to={end}&kiosk&theme=light'
with tempfile.TemporaryDirectory(prefix='trova-534-chrome-') as profile:
 with open('/private/tmp/trova-534-chrome.log','w') as log:
  p=subprocess.Popen(['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome','--headless','--disable-gpu','--no-first-run','--remote-debugging-address=127.0.0.1','--remote-debugging-port=0','--user-data-dir='+profile,'--window-size=1600,1600','about:blank'],stdout=log,stderr=subprocess.STDOUT)
 try:
  for _ in range(60):
   try:
    debug_port=int((Path(profile)/'DevToolsActivePort').read_text().splitlines()[0])
    with urllib.request.urlopen(f'http://127.0.0.1:{debug_port}/json') as r:targets=json.load(r)
    target=next(t for t in targets if t.get('type')=='page');break
   except Exception:time.sleep(.5)
  else:raise RuntimeError('headless Chrome did not start')
  ws=websocket.create_connection(target['webSocketDebuggerUrl'],suppress_origin=True,timeout=30);seq=0
  def call(method,params=None):
   global seq
   seq+=1;wanted=seq;ws.send(json.dumps({'id':wanted,'method':method,'params':params or {}}))
   while True:
    r=json.loads(ws.recv())
    if r.get('id')==wanted:
     if 'error' in r:raise RuntimeError(r['error'])
     return r.get('result',{})
  call('Page.enable');call('Emulation.setDeviceMetricsOverride',{'width':1600,'height':1600,'deviceScaleFactor':1,'mobile':False});call('Page.navigate',{'url':url});time.sleep(25)
  check=call('Runtime.evaluate',{'expression':'document.body.innerText','returnByValue':True})['result']['value']
  if 'Trova' not in check or '앱 CPU' not in check:raise RuntimeError('unexpected Grafana page')
  screen=call('Page.captureScreenshot',{'format':'png','captureBeyondViewport':True})
  (OUT/'local-grafana.png').write_bytes(base64.b64decode(screen['data']))
  (OUT/'raw/grafana-capture.json').write_text(json.dumps({'url':url,'captured_at':time.time(),'from':start,'to':end,'timezone':'Asia/Seoul','body_text_excerpt':check[:800]},ensure_ascii=False,indent=2))
  ws.close();print('로컬 Grafana 캡처 저장 완료')
 finally:
  p.terminate()
  try:p.wait(timeout=5)
  except subprocess.TimeoutExpired:p.kill();p.wait()
