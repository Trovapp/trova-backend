"""Part B 실행 — 지금 일정 생성 기능으로 요청 20개를 돌리고 채점에 필요한 것을 모은다.

지금 기능은 영상 1개의 장소 목록만 받는다(POST /api/places/videos/{jobId}/itinerary). 요청의 일수·여러 영상 조건은
전달할 방법이 없어, 영상마다 그대로 돌린 결과를 기록한다(없는 기능을 흉내 내지 않는다). 시작일이 있는 요청은
실제 흐름처럼 여행을 확정(confirm-trip)해 날짜를 받고, 받은 뒤 그 여행을 지운다.

준비: Part A를 --keep --repeat 1로 돌려 영상별 작업을 남긴다(kept_jobs.json).
사용: python eval/itinerary/run.py --jobs <Part A 결과>/kept_jobs.json [--max-google-calls 300]
결과: eval/itinerary/results/<날짜>_<커밋>/raw/<요청>.json, hours.json, meta.json
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import os
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import common  # noqa: E402

HERE = Path(__file__).resolve().parent
HOURS_CACHE = HERE / "hours_cache.json"
JOB_TIMEOUT_SECONDS = 300
HOURS_MATCH_M = 300
CLOSED_DAY_SEARCH_DAYS = 14


def haversine_m(a, b) -> float:
    lat1, lng1, lat2, lng2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lng2 - lng1) / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


def job_state(conn, job_id):
    cur = conn.cursor()
    cur.execute("select status, updated_at from processing_jobs where id=%s and user_id=%s", (job_id, common.user_id()))
    return cur.fetchone()


def snapshot(conn, job_id) -> list[dict]:
    cur = conn.cursor()
    cur.execute("""select id, place_name, region, latitude, longitude, day_number, order_in_day
                   from saved_places where processing_job_id=%s and user_id=%s order by id""", (job_id, common.user_id()))
    return [dict(zip(["id", "name", "region", "latitude", "longitude", "day", "order"], r)) for r in cur.fetchall()]


def gemini_calls_since(conn, job_id, since) -> list[dict]:
    cur = conn.cursor()
    cur.execute("""select operation, success, latency_ms, prompt_tokens, response_tokens from api_call_logs
                   where job_id=%s and provider='gemini' and created_at >= %s order by id""", (job_id, since))
    return [dict(zip(["operation", "success", "latency_ms", "prompt_tokens", "response_tokens"], r)) for r in cur.fetchall()]


def generate(conn, job_id) -> dict:
    before = job_state(conn, job_id)
    started_db = before[1]
    t0 = time.time()
    status, body = common.api("POST", f"/api/places/videos/{job_id}/itinerary")
    if status != 202:
        return {"result": "REQUEST_FAILED", "http": status, "body": body}
    while time.time() - t0 < JOB_TIMEOUT_SECONDS:
        st, updated = job_state(conn, job_id)
        if updated > started_db and st in ("DONE", "FAILED"):
            return {"result": st, "seconds": round(time.time() - t0, 1), "since": str(started_db)}
        time.sleep(1)
    return {"result": "TIMEOUT", "seconds": round(time.time() - t0, 1)}


# ---------- Google 영업시간(Text Search, 측정용) ----------

class GoogleHours:
    def __init__(self, max_calls: int):
        self.key = os.environ.get("GOOGLE_PLACES_API_KEY", "")
        self.cache = json.loads(HOURS_CACHE.read_text()) if HOURS_CACHE.exists() else {}
        self.calls = 0
        self.max_calls = max_calls

    def get(self, place: dict):
        if place["latitude"] is None:
            return {"status": "NO_COORD"}
        ck = f"{place['name']}|{place['latitude']:.5f},{place['longitude']:.5f}"
        if ck in self.cache:
            return self.cache[ck]
        if not self.key:
            return {"status": "NO_KEY"}
        if self.calls >= self.max_calls:
            sys.exit(f"Google 호출이 상한({self.max_calls})에 닿아 멈춘다 — 무료 한도 보호.")
        self.calls += 1
        body = json.dumps({"textQuery": place["name"], "languageCode": "ko", "regionCode": "KR",
                           "locationBias": {"circle": {"center": {"latitude": place["latitude"],
                                                                  "longitude": place["longitude"]},
                                                       "radius": float(HOURS_MATCH_M)}}}).encode()
        req = urllib.request.Request("https://places.googleapis.com/v1/places:searchText", data=body, method="POST",
                                     headers={"Content-Type": "application/json", "X-Goog-Api-Key": self.key,
                                              "X-Goog-FieldMask": "places.id,places.displayName,places.location,places.regularOpeningHours"})
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                places = json.loads(r.read()).get("places", [])
        except Exception as e:  # 실패도 그대로 기록(판정 불가)
            return {"status": "ERROR", "error": str(e)[:200]}
        near = []
        for p in places:
            loc = p.get("location") or {}
            if "latitude" in loc:
                d = haversine_m((place["latitude"], place["longitude"]), (loc["latitude"], loc["longitude"]))
                if d <= HOURS_MATCH_M:
                    near.append((d, p))
        if not near:
            result = {"status": "NO_MATCH"}
        else:
            d, p = min(near, key=lambda x: x[0])
            hours = p.get("regularOpeningHours")
            result = {"status": "OK" if hours else "NO_HOURS", "google_id": p.get("id"),
                      "google_name": (p.get("displayName") or {}).get("text"), "distance_m": round(d),
                      "periods": (hours or {}).get("periods")}
        self.cache[ck] = result
        HOURS_CACHE.write_text(json.dumps(self.cache, ensure_ascii=False, indent=1))
        return result


def open_on(periods, date: dt.date):
    """그 날짜(요일)에 문을 여는지. periods가 없으면 None(판정 불가). Google day: 0=일요일."""
    if not periods:
        return None
    gday = (date.weekday() + 1) % 7
    if any("close" not in p for p in periods):  # 24시간
        return True
    return any(p.get("open", {}).get("day") == gday for p in periods)


def pick_start(kind, places, hours, today: dt.date):
    if kind is None:
        return None, None
    if kind == "weekend":
        return today + dt.timedelta(days=(5 - today.weekday()) % 7 or 7), None
    if kind == "weekday":
        return today + dt.timedelta(days=(2 - today.weekday()) % 7 or 7), None
    for i in range(1, CLOSED_DAY_SEARCH_DAYS + 1):
        day = today + dt.timedelta(days=i)
        closed = [p["name"] for p in places if open_on(hours.get(p["id"], {}).get("periods"), day) is False]
        if closed:
            return day, closed
    return None, "휴무일 조건 만들 수 없음"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jobs", required=True)
    ap.add_argument("--max-google-calls", type=int, default=300)
    ap.add_argument("--only")
    args = ap.parse_args()
    jobs = {j["video_no"]: j["job_id"] for j in json.loads(Path(args.jobs).read_text())}
    reqs = json.loads((HERE / "requests.json").read_text())["requests"]
    if args.only:
        keep = set(args.only.split(","))
        reqs = [r for r in reqs if r["id"] in keep]
    out = common.new_result_dir("itinerary")
    (out / "raw").mkdir(exist_ok=True)
    common.write_meta(out, {"part": "B", "jobs_file": args.jobs, "requests": [r["id"] for r in reqs]})
    conn = common.db()
    google = GoogleHours(args.max_google_calls)
    today = dt.date.today()
    for req in reqs:
        missing = [v for v in req["videos"] if v not in jobs]
        rec = {"request": req, "videos": []}
        if missing:
            rec["result"] = f"영상 작업 없음: {missing}"
        else:
            for v in req["videos"]:
                job_id = jobs[v]
                before = snapshot(conn, job_id)
                gen = generate(conn, job_id)
                after = snapshot(conn, job_id)
                calls = gemini_calls_since(conn, job_id, gen.get("since")) if gen.get("since") else []
                rec["videos"].append({"video_no": v, "job_id": job_id, "generate": gen,
                                      "input_place_ids": [p["id"] for p in before], "places": after, "gemini": calls})
            all_places = [p for vd in rec["videos"] for p in vd["places"]]
            hours = {p["id"]: google.get(p) for p in all_places}
            start, closed_note = pick_start(req["start"], all_places, hours, today)
            rec["start_date"] = str(start) if start else None
            rec["closed_day_note"] = closed_note
            rec["hours"] = {str(k): v for k, v in hours.items()}
            if start:  # 실제 흐름처럼 여행을 확정해 날짜를 받고, 기록한 뒤 지운다
                trips = []
                # confirm-trip은 그 영상으로 이미 만든 여행이 있으면 그걸 돌려준다 — 측정 전부터 있던 여행은 지우지 않는다.
                _, existing = common.api("GET", "/api/trips")
                existing_ids = {t["id"] for t in (existing or [])}
                for vd in rec["videos"]:
                    st, trip = common.api("POST", f"/api/places/videos/{vd['job_id']}/confirm-trip",
                                          {"title": f"eval {req['id']}", "startDate": str(start)})
                    if st == 200:
                        _, detail = common.api("GET", f"/api/trips/{trip['id']}")
                        reused = trip["id"] in existing_ids
                        trips.append({"video_no": vd["video_no"], "trip": trip, "detail": detail, "reused_existing": reused})
                        if not reused:
                            common.api("DELETE", f"/api/trips/{trip['id']}")
                    else:
                        trips.append({"video_no": vd["video_no"], "error": st})
                rec["trips"] = trips
        (out / "raw" / f"{req['id']}.json").write_text(json.dumps(rec, ensure_ascii=False, indent=1, default=str))
        print(req["id"], rec.get("result") or [vd["generate"]["result"] for vd in rec["videos"]], rec.get("start_date"), flush=True)
    print("Google 호출", google.calls, "결과:", out)


if __name__ == "__main__":
    main()
