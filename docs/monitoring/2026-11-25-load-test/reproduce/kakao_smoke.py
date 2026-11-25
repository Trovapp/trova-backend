"""One real Kakao dependency probe INSIDE clone app; key comes only from Compose env_file.
This is not a user-path throughput benchmark. Never prints credentials or response body.
"""
import json,os,time,urllib.request,urllib.parse,urllib.error
from pathlib import Path
assert Path('/evidence').is_dir(), 'Run only inside isolated app container'
url='https://dapi.kakao.com/v2/local/search/keyword.json?'+urllib.parse.urlencode({'query':'경복궁','size':1})
req=urllib.request.Request(url,headers={'Authorization':'KakaoAK '+os.environ['KAKAO_REST_API_KEY']})
start=time.time();row={'provider':'kakao','operation':'keyword_search','requests':1,'started':start,'scope':'one dependency probe inside isolated app; separate from application counters'}
try:
 with urllib.request.urlopen(req,timeout=15) as r:
  body=json.load(r);row.update(status=r.status,documents=len(body.get('documents',[])))
except urllib.error.HTTPError as e:row.update(status=e.code)
except Exception as e:row.update(status=0,error=type(e).__name__)
row['duration_ms']=(time.time()-start)*1000
Path('/evidence/kakao-dependency-smoke.json').write_text(json.dumps(row,ensure_ascii=False,indent=2))
print(json.dumps(row,ensure_ascii=False))
