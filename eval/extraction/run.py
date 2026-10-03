"""Part A 실행 — 고른 영상(videos.csv의 selected=Y)을 로컬 서버에 넣고 결과·시간·토큰을 모은다.

운영과 같은 경로(POST /api/shares → 다운로드/Gemini → 카카오 지오코딩 → 후보 선택 → 검증)를 그대로 탄다.
영상마다 --repeat 번 돌리고(reanalyze=true로 매번 새로 분석), 끝나면 그 작업의 데이터를 개발 DB에서 지운다.

사용:  python eval/extraction/run.py --repeat 2 [--only 1,5,9] [--keep]
결과:  eval/extraction/results/<날짜>_<커밋>/raw/<영상번호>_r<회차>.json, meta.json
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import common  # noqa: E402

HERE = Path(__file__).resolve().parent
POLL_SECONDS = 2
JOB_TIMEOUT_SECONDS = 900  # 서버 파이프라인 제한(5분) + 지오코딩·선택·검증 여유


def selected_videos(only: set[int] | None) -> list[dict]:
    with open(HERE / "videos.csv", newline="") as f:
        rows = list(csv.DictReader(f))
    picked = [r for r in rows if r["selected"].strip().upper() == "Y"]
    if only:
        picked = [r for r in picked if int(r["video_no"]) in only]
    if not picked:
        sys.exit("videos.csv에 selected=Y인 영상이 없다.")
    return picked


def wait_for_job(job_id: int) -> dict:
    start = time.time()
    while time.time() - start < JOB_TIMEOUT_SECONDS:
        status, jobs = common.api("GET", "/api/places/pending")
        job = next((j for j in (jobs or []) if j.get("jobId") == job_id), None) if status == 200 else None
        if job is None:
            return {"status": "DONE_OR_GONE"}
        if job.get("status") == "FAILED":
            return {"status": "FAILED", "failureReason": job.get("failureReason")}
        time.sleep(POLL_SECONDS)
    return {"status": "TIMEOUT"}


def collect(conn, job_id: int) -> dict:
    cur = conn.cursor()
    cur.execute("select status, error_message, created_at, updated_at from processing_jobs where id=%s and user_id=%s",
                (job_id, common.user_id()))
    row = cur.fetchone()
    job = {"status": row[0], "error_message": row[1],
           "db_seconds": (row[3] - row[2]).total_seconds() if row[2] and row[3] else None} if row else None
    cur.execute("""select place_name, region, category, latitude, longitude, address, road_address, kakao_place_url
                   from saved_places where processing_job_id=%s and user_id=%s order by id""", (job_id, common.user_id()))
    places = [dict(zip(["name", "region", "category", "latitude", "longitude", "address", "road_address",
                        "kakao_place_url"], r)) for r in cur.fetchall()]
    cur.execute("""select provider, operation, success, latency_ms, prompt_tokens, response_tokens, total_tokens, error_message
                   from api_call_logs where job_id=%s order by id""", (job_id,))
    calls = [dict(zip(["provider", "operation", "success", "latency_ms", "prompt_tokens", "response_tokens",
                       "total_tokens", "error_message"], r)) for r in cur.fetchall()]
    return {"job": job, "places": places, "api_calls": calls}


def cleanup(conn, job_id: int) -> None:
    cur = conn.cursor()
    cur.execute("delete from saved_places where processing_job_id=%s and user_id=%s", (job_id, common.user_id()))
    cur.execute("delete from processing_jobs where id=%s and user_id=%s", (job_id, common.user_id()))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repeat", type=int, default=2)
    ap.add_argument("--only", help="영상 번호 몇 개만(쉼표)")
    ap.add_argument("--keep", action="store_true", help="측정 데이터를 지우지 않는다(Part B에 쓸 때)")
    ap.add_argument("--pause", type=float, default=5.0, help="영상 사이 대기(초) — Gemini 분당 한도 보호")
    ap.add_argument("--resume", help="중단된 결과 폴더 — raw 파일이 이미 있는 (영상, 회차)는 건너뛰고 이어서 돈다")
    args = ap.parse_args()

    only = {int(x) for x in args.only.split(",")} if args.only else None
    videos = selected_videos(only)
    if args.resume:
        out = Path(args.resume)
        meta = json.loads((out / "meta.json").read_text())
        meta.setdefault("resumed_at", []).append(time.strftime("%Y-%m-%dT%H:%M:%S"))
        (out / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2))
    else:
        out = common.new_result_dir("extraction")
        (out / "raw").mkdir(exist_ok=True)
        common.write_meta(out, {"part": "A", "repeat": args.repeat, "videos": [int(v["video_no"]) for v in videos],
                                "kept_jobs": args.keep})
    conn = common.db()
    kept_file = out / "kept_jobs.json"
    kept = json.loads(kept_file.read_text()) if kept_file.exists() else []
    for rep in range(1, args.repeat + 1):
        for v in videos:
            no = int(v["video_no"])
            if (out / "raw" / f"{no:02d}_r{rep}.json").exists():
                continue
            t0 = time.time()
            status, body = common.api("POST", "/api/shares", {"url": v["url"], "reanalyze": True})
            record = {"video_no": no, "repeat": rep, "url": v["url"], "submit_status": status}
            if status not in (200, 202) or not isinstance(body, dict):
                record.update({"result": "SUBMIT_FAILED", "response": body})
            else:
                job_id = body["jobId"]
                record["job_id"] = job_id
                record["wait"] = wait_for_job(job_id)
                record["wall_seconds"] = round(time.time() - t0, 1)
                # 측정이 30분 넘게 이어지면 pooler가 쉬던 연결을 끊는다(2026-10-03 실측) — 끊기면 다시 붙어 한 번 더 한다.
                try:
                    record.update(collect(conn, job_id))
                except Exception:
                    conn = common.db()
                    record.update(collect(conn, job_id))
                if args.keep:
                    kept.append({"video_no": no, "repeat": rep, "job_id": job_id})
                    kept_file.write_text(json.dumps(kept, indent=2))
                else:
                    cleanup(conn, job_id)
            (out / "raw" / f"{no:02d}_r{rep}.json").write_text(
                json.dumps(record, ensure_ascii=False, indent=2, default=str))
            print(f"[{rep}/{args.repeat}] 영상 {no}: {record.get('wait', {}).get('status', record.get('result'))}, "
                  f"장소 {len(record.get('places', []))}곳, {record.get('wall_seconds')}초", flush=True)
            time.sleep(args.pause)
    print("결과:", out)


if __name__ == "__main__":
    main()
