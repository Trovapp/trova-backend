"""Part B — 일정 에이전트(#106, 설계 B)를 같은 요청 20개로 돌려 지금 기능(run.py)과 같은 채점기(score.py)로 잰다.

에이전트는 여러 영상·일수·시작일을 한 번에 받는다(POST /api/trip-drafts). 요청 문장은 서버가 코드로 바로 읽는
정해진 표현("1박 2일", "2026-10-10부터")으로 만든다 — 문장 해석 능력이 아니라 일정 품질을 비교하려고.
시작 전 질문(영상 지역이 100km 넘게 떨어짐)이 오면 "지역별로 나누기(SPLIT)"로 답하고, 질문이 있었다는 것도 기록한다.
초안은 승인하지 않는다(여행을 만들지 않음) — 측정이 끝나면 초안을 지운다.

결과 형식은 run.py와 같다(영상별 places에 day/order) — score.py를 그대로 쓴다. 시작일과 영업시간 판정은 run.py와 같은
측정용 Google 캐시(hours_cache.json)로 정한다 — 에이전트가 안에서 받은 영업시간과는 따로, 같은 기준으로 채점한다.

준비: 에이전트가 들어간 서버를 로컬에 띄운다(개발 DB). Part A를 --keep으로 돌려 남긴 작업(kept_jobs.json)을 쓴다.
사용: EVAL_BASE_URL=http://localhost:8090 python eval/itinerary/run_agent.py --jobs <Part A 결과>/kept_jobs.json
결과: eval/itinerary/results/<날짜>_<커밋>_agent/raw/<요청>.json, meta.json
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import common  # noqa: E402
from run import GoogleHours, pick_start, snapshot  # noqa: E402

HERE = Path(__file__).resolve().parent
DRAFT_TIMEOUT_SECONDS = 300


def request_message(days: int, start: dt.date | None) -> str:
    period = "당일치기" if days == 1 else f"{days - 1}박 {days}일"
    return f"{start.isoformat()}부터 {period}" if start else period


def wait_draft(draft_id: int) -> dict:
    t0 = time.time()
    while time.time() - t0 < DRAFT_TIMEOUT_SECONDS:
        st, d = common.api("GET", f"/api/trip-drafts/{draft_id}")
        if st == 200 and d["status"] not in ("PENDING", "PROCESSING"):
            return d
        time.sleep(1)
    return {"status": "TIMEOUT"}


def gemini_calls_between(conn, since, until) -> list[dict]:
    # 에이전트 호출은 처리 작업에 묶이지 않는다(job_id 없음) — 이 요청을 도는 동안의 trip-plan.* 호출을 센다(요청은 하나씩 돈다).
    cur = conn.cursor()
    cur.execute("""select operation, success, latency_ms, prompt_tokens, response_tokens from api_call_logs
                   where provider='gemini' and operation like 'trip-plan%%' and created_at >= %s and created_at <= %s
                   order by id""", (since, until))
    return [dict(zip(["operation", "success", "latency_ms", "prompt_tokens", "response_tokens"], r)) for r in cur.fetchall()]


def google_calls_between(conn, since, until) -> int:
    cur = conn.cursor()
    cur.execute("""select count(*) from api_call_logs where provider='google-places' and operation='text-search-hours'
                   and created_at >= %s and created_at <= %s""", (since, until))
    return cur.fetchone()[0]


def db_now(conn):
    # api_call_logs.created_at은 서버(같은 기기)가 자기 시간대의 LocalDateTime.now()로 적는다 — 같은 기준인 이 기기 시각을 쓴다.
    return dt.datetime.now()


def to_videos(req, jobs, inputs_by_video, draft, plan, seconds, gemini) -> list[dict]:
    """초안을 run.py의 영상별 기록 모양으로 바꾼다. 일정에 들어간 장소만 places에(뺀 장소는 빠짐), 순서는 1부터."""
    pos = {}
    for day in (plan or {}).get("days", []):
        for order, it in enumerate(day["items"], start=1):
            pos[it["placeId"]] = (day["day"], order)
    videos = []
    for i, v in enumerate(req["videos"]):
        inputs = inputs_by_video[v]
        places = [{**p, "day": pos[p["id"]][0], "order": pos[p["id"]][1]} for p in inputs if p["id"] in pos]
        # 다른 영상 장소인데 이 영상 입력에 없는 건 score의 '없는장소'가 잡도록 첫 영상에 붙인다(에이전트가 만든 가짜 장소 대비).
        if i == 0:
            known = {p["id"] for vs in inputs_by_video.values() for p in vs}
            for pid, (d, o) in pos.items():
                if pid not in known:
                    places.append({"id": pid, "name": f"(입력에 없는 장소 {pid})", "region": None,
                                   "latitude": None, "longitude": None, "day": d, "order": o})
        videos.append({
            "video_no": v, "job_id": jobs[v],
            "generate": {"result": "DONE" if draft.get("status") == "READY" else draft.get("status"),
                         # 시간·Gemini 호출은 요청 단위라 첫 영상에만 적는다(score가 영상별로 더한다).
                         "seconds": seconds if i == 0 else None},
            "input_place_ids": [p["id"] for p in inputs],
            "places": places,
            "gemini": gemini if i == 0 else [],
        })
    return videos


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jobs", required=True)
    ap.add_argument("--max-google-calls", type=int, default=300)
    ap.add_argument("--only")
    ap.add_argument("--requests", default=str(HERE / "requests.json"))
    ap.add_argument("--server-commit", required=True, help="측정한 서버 코드의 커밋(에이전트 브랜치)")
    args = ap.parse_args()
    jobs = {j["video_no"]: j["job_id"] for j in json.loads(Path(args.jobs).read_text())}
    reqs = json.loads(Path(args.requests).read_text())["requests"]
    if args.only:
        keep = set(args.only.split(","))
        reqs = [r for r in reqs if r["id"] in keep]
    out = common.new_result_dir("itinerary", suffix="agent")
    (out / "raw").mkdir(exist_ok=True)
    common.write_meta(out, {"part": "B", "mode": "agent", "jobs_file": args.jobs, "requests": [r["id"] for r in reqs],
                            "base_url": common.base_url(),
                            "server_commit": args.server_commit})
    conn = common.db()
    google = GoogleHours(args.max_google_calls)
    today = dt.date.today()
    draft_ids = []
    for req in reqs:
        rec = {"request": req, "mode": "agent", "videos": []}
        missing = [v for v in req["videos"] if v not in jobs]
        if missing:
            rec["result"] = f"영상 작업 없음: {missing}"
        else:
            inputs_by_video = {v: snapshot(conn, jobs[v]) for v in req["videos"]}
            all_inputs = [p for v in req["videos"] for p in inputs_by_video[v]]
            hours = {p["id"]: google.get(p) for p in all_inputs}
            start, closed_note = pick_start(req["start"], all_inputs, hours, today)
            message = request_message(req["days"], start)
            since = db_now(conn)
            t0 = time.time()
            st, body = common.api("POST", "/api/trip-drafts", {"jobIds": [jobs[v] for v in req["videos"]], "message": message})
            if st != 202:
                rec["result"] = f"REQUEST_FAILED {st}"
            else:
                draft_ids.append(body["draftId"])
                draft = wait_draft(body["draftId"])
                question = None
                if draft.get("status") == "NEEDS_INPUT":
                    question = draft.get("question")
                    common.api("POST", f"/api/trip-drafts/{body['draftId']}/answer", {"choice": "SPLIT"})
                    draft = wait_draft(body["draftId"])
                seconds = round(time.time() - t0, 1)
                until = db_now(conn)
                plan = json.loads(draft["draftJson"]) if draft.get("draftJson") else None
                gemini = gemini_calls_between(conn, since, until)
                rec["videos"] = to_videos(req, jobs, inputs_by_video, draft, plan, seconds, gemini)
                rec["agent"] = {
                    "draft_id": body["draftId"], "message": message, "status": draft.get("status"),
                    "question": question, "answer": draft.get("answer"), "gemini_calls": draft.get("geminiCalls"),
                    "agent_google_hours_calls": google_calls_between(conn, since, until),
                    "error": draft.get("errorMessage"),
                    "excluded": (plan or {}).get("excluded"), "lodging": (plan or {}).get("lodging"),
                    "fixes": (plan or {}).get("fixes"), "problems": (plan or {}).get("problems"),
                    "draft": plan,
                }
            # 뺀 장소 판정(excluded_check.py)에 쓰려고 입력 장소 전체(좌표 포함)를 남긴다.
            rec["inputs"] = all_inputs
            rec["start_date"] = str(start) if start else None
            rec["closed_day_note"] = closed_note
            rec["hours"] = {str(k): v for k, v in hours.items()}
        (out / "raw" / f"{req['id']}.json").write_text(json.dumps(rec, ensure_ascii=False, indent=1, default=str))
        a = rec.get("agent", {})
        print(req["id"], rec.get("result") or a.get("status"), rec.get("start_date"), f"Gemini {a.get('gemini_calls')}",
              "질문" if a.get("question") else "", flush=True)
        time.sleep(3)  # Gemini 분당 한도 보호
    # 측정 초안 정리(승인하지 않았으니 여행은 없다).
    cur = conn.cursor()
    if draft_ids:
        cur.execute("delete from trip_drafts where id = any(%s) and user_id=%s", (draft_ids, common.user_id()))
    print("측정용 Google 호출", google.calls, "초안 정리", len(draft_ids), "결과:", out)


if __name__ == "__main__":
    main()
