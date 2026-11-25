"""101 requests to gap-less synthetic trip: verify shared quota with zero provider calls."""
import json,time,urllib.request,urllib.error,gzip
from pathlib import Path
from run import tokens,metrics,counts,APP,OUT
before=counts(metrics());results=[];t=tokens()[298]
for n in range(1,102):
 req=urllib.request.Request(APP+'/api/trips/299/days/1/gap-recommendations',headers={'Authorization':'Bearer '+t})
 start=time.time()
 try:
  with urllib.request.urlopen(req,timeout=10) as r:status=r.status;body=r.read().decode()
 except urllib.error.HTTPError as e:status=e.code;body=e.read().decode()
 results.append({'request':n,'status':status,'body':json.loads(body),'ms':(time.time()-start)*1000})
 time.sleep(.05)
after=counts(metrics());(OUT/'gap-local-quota.json').write_text(json.dumps({'before':before,'after':after,'results':results},ensure_ascii=False,indent=2))
assert all(r['status']==200 and r['body']==[] for r in results[:100])
assert results[100]['status']==429
assert before['places']==after['places'] and before['gemini_metric_units']==after['gemini_metric_units']
print('빈 시간 없음: 100건 200 → 101번째 429; 외부 호출 증분 0 확인')
