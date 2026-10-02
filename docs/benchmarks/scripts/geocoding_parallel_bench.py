# 로컬(개발 DB)에서 수정 전(8090)·후(8091) 서버에 같은 영상을 번갈아 제출하고, 서버 로그의 지오코딩 구간 시간을 모은다.
import sys, os, re, time, json
sys.path.insert(0,'build'); sys.path.insert(0,os.environ["BENCH_TMP"]); import authz
from qa import db
T=os.environ["BENCH_TMP"]
SERVERS={"before":("http://localhost:8090",T+"/geo-before.log"),"after":("http://localhost:8091",T+"/geo-after.log")}
URLS=["https://www.youtube.com/shorts/8vmpPuFwQ94","https://www.youtube.com/shorts/ppCfDrwbHMk",
      "https://www.youtube.com/shorts/gh-njZcrDJQ","https://www.youtube.com/shorts/YrN-JLPp0wg","https://www.youtube.com/shorts/NvdrCwcTnlE"]
PASSES=int(sys.argv[1]) if len(sys.argv)>1 else 2
def run(label,url):
    authz.BASE=SERVERS[label][0]
    st,b=authz.req(1,"POST","/api/shares",{"url":url,"reanalyze":True})
    if st not in (200,201,202): print("제출 실패",label,st,b); return None
    job=b["jobId"]; t0=time.time()
    while time.time()-t0<240:
        st,jobs=authz.req(1,"GET","/api/places/pending")
        j=next((x for x in jobs if x["jobId"]==job),None)
        if j is None or j["status"]=="FAILED": break
        time.sleep(1)
    status = "FAILED" if j else "DONE"
    m=re.search(rf"ProcessingJob {job} 지오코딩 (\d+)곳: (\d+)ms", open(SERVERS[label][1]).read())
    return dict(label=label,url=url[-11:],job=job,status=status,places=int(m.group(1)) if m else None,ms=int(m.group(2)) if m else None,total=round(time.time()-t0,1))
rows=[]
for p in range(PASSES):
    for i,u in enumerate(URLS):
        order=["before","after"] if (p+i)%2==0 else ["after","before"]
        for lab in order:
            r=run(lab,u); print(json.dumps(r,ensure_ascii=False),flush=True); rows.append(r)
cur=db('dev').cursor()
for r in rows:
    if r: cur.execute("select count(*) from api_call_logs where job_id=%s and provider='kakao'",(r['job'],)); r['kakao_calls']=cur.fetchone()[0]
json.dump(rows,open(T+"/geo_bench_rows.json","w"),ensure_ascii=False,indent=1)
print("저장", T+"/geo_bench_rows.json")
