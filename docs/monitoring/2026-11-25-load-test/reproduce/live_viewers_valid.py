"""64 total SSE clients: 10 in the shared trip, 54 in their own trips. Product membership limit respected."""
import asyncio,json,time
from pathlib import Path
from sse import open_one,close_all
async def main():
 results=await asyncio.gather(*(open_one(u,301 if u<=10 else u) for u in range(1,65)))
 Path('/out/live-valid-policy-viewers.json').write_text(json.dumps({'at':time.time(),'shared_trip_members':10,'shared_viewers':10,'other_trip_viewers':54,'results':results},indent=2))
 if not all(r['status']==200 and r['ready'] for r in results):raise RuntimeError('64 viewers not admitted')
 Path('/out/live-valid-policy-holding').write_text(str(time.time()))
 await asyncio.sleep(180);await close_all()
asyncio.run(main())
