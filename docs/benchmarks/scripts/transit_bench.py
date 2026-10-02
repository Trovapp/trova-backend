"""공항·항구 등 교통 시설 오탐을 반복 실측한다(#91). 사용: GEMINI_API_KEY=... python transit_bench.py <pipeline-test 경로> <라벨> <결과.json>"""
import json, os, subprocess, sys, time, re
pt, label, out = sys.argv[1], sys.argv[2], sys.argv[3]
CASES = [("김해", "https://www.youtube.com/shorts/8vmpPuFwQ94", 5), ("묵호", "https://www.youtube.com/shorts/ppCfDrwbHMk", 3)]
TRANSIT = re.compile(r"공항|항$|포구|터미널|역$|정류장")
rows = []
for name, url, n in CASES:
    for i in range(n):
        t = time.time()
        p = subprocess.run([sys.executable, os.path.join(pt, "run_pipeline.py"), url, f"/tmp/transit-bench-{label}-{name}-{i}"],
                           capture_output=True, text=True, cwd=pt, timeout=300)
        try:
            places = [x["name"] for x in json.loads(p.stdout)["places"]]
        except Exception:
            places = None
        transit = [x for x in (places or []) if TRANSIT.search(x)]
        rows.append({"video": name, "run": i + 1, "seconds": round(time.time() - t, 1), "places": places, "transit": transit,
                     "error": None if places is not None else p.stderr[-300:]})
        print(f"[{label}] {name} {i+1}회 {rows[-1]['seconds']}초 장소 {len(places or [])}곳 교통 {transit} {'' if places is not None else '오류'}", flush=True)
        time.sleep(8)
json.dump(rows, open(out, "w"), ensure_ascii=False, indent=1)
