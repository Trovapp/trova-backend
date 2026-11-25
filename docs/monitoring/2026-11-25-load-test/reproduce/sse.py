"""SSE admission/closed-client cleanup probe inside disposable Docker network."""
import asyncio,json,time,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent))
from run import tokens
T=tokens();connections=[];rows=[];readers=[]
async def drain(reader):
 try:
  while await reader.read(8192):pass
 except Exception:pass
async def open_one(user,trip=301):
 started=time.time()
 try:
  r,w=await asyncio.wait_for(asyncio.open_connection('app',8080),10)
  w.write((f'GET /api/trips/{trip}/events HTTP/1.1\r\nHost: app\r\nAuthorization: Bearer {T[user-1]}\r\nAccept: text/event-stream\r\n\r\n').encode());await w.drain()
  header=await asyncio.wait_for(r.readuntil(b'\r\n\r\n'),65);status=int(header.split(b' ')[1])
  # Reading through first SSE event demonstrates stream registration, not just headers.
  ready=False
  if status==200:
   chunk=await asyncio.wait_for(r.read(4096),10);ready=b'ready' in chunk
   connections.append((r,w));readers.append(asyncio.create_task(drain(r)))
  else:w.close()
  return {'user':user,'status':status,'ready':ready,'admission_ms':(time.time()-started)*1000}
 except Exception as e:return {'user':user,'status':0,'error':type(e).__name__,'admission_ms':(time.time()-started)*1000}
async def close_all():
 for _,w in connections:w.close()
 connections.clear()
 for task in readers:task.cancel()
 readers.clear()
async def save(label,results):
 row={'label':label,'at':time.time(),'results':results};rows.append(row)
 Path('/out/sse-results.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
 print(label,{s:sum(r['status']==s for r in results) for s in set(r['status'] for r in results)},flush=True)
async def main():
 # Closed TCP clients can remain admitted through a successful first heartbeat write.
 # Drain previous probes for three heartbeat periods before counting slots.
 await asyncio.sleep(75)
 await save('same-user-4',await asyncio.gather(*(open_one(1) for _ in range(4))))
 await close_all()
 await save('immediate-reopen-after-client-close',[await open_one(1)])
 await close_all();await asyncio.sleep(30)
 await save('reopen-after-30s',[await open_one(1)])
 await close_all();await asyncio.sleep(45)
 await save('reopen-after-75s',[await open_one(1)]);await close_all()
 result=[]
 for _ in range(5):
  result.append(await open_one(2));await close_all();await asyncio.sleep(2)
 await save('navigation-5-every-2s',result);await asyncio.sleep(75)
 previous=0
 for n in [16,64,128,256,257]:
  results=await asyncio.gather(*(open_one(u,u) for u in range(previous+1,n+1)))
  await save('accumulated-'+str(n),results)
  if any(x['status']==0 for x in results):break
  previous=n;await asyncio.sleep(8)
 await close_all();await asyncio.sleep(75)
 if '--hold' in sys.argv:
  await save('live-viewers-64',await asyncio.gather(*(open_one(u,301 if u<=10 else u) for u in range(1,65))))
  Path('/out/sse-holding').write_text(str(time.time()))
  await asyncio.sleep(240);await close_all()
if __name__=='__main__':asyncio.run(main())
