"""One 64-viewer session for an explicit concurrent-write probe after all other work."""
import asyncio,json,time
from pathlib import Path
from sse import open_one,close_all
async def main():
 results=await asyncio.gather(*(open_one(u,301 if u<=10 else u) for u in range(1,65)))
 Path('/out/live-concurrency-viewers.json').write_text(json.dumps({'at':time.time(),'results':results},indent=2))
 if not all(r['status']==200 and r['ready'] for r in results):raise RuntimeError('64 viewers not admitted')
 Path('/out/live-concurrency-holding').write_text(str(time.time()))
 await asyncio.sleep(120);await close_all()
asyncio.run(main())
