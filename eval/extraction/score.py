"""Part A 채점 — 정답(answers.csv)과 실행 결과(raw/*.json)를 비교한다.

판정(장소 단위)
  맞음      이름이 정규화 후 같거나, 한쪽이 다른 쪽을 포함하고 정답 좌표에서 200m 안
  확인 필요 포함만 맞고 좌표로 확인할 수 없거나 200m 밖, 또는 후보가 여럿 → 지표에서 빼고 review_needed.csv로
  오탐      정답에 없는 결과 — 유형: 경유지(정답에 is_destination=N으로 적힌 곳) / 다른 지역(정답 좌표에서 50km 밖) / 정답에 없는 장소
  누락      맞음으로 잡히지 않은 정답
manual_matches.csv에 사용자가 적은 판정(decision=match/false_positive/skip)이 있으면 그것이 우선한다.

사용:  python eval/extraction/score.py <결과 폴더> [--usd-krw 1390 --usd-krw-source "출처, 날짜"]
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import os
import re
import sys
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
COORD_MATCH_M = 200
OTHER_REGION_KM = 50
GEMINI_INPUT_USD_PER_M = 0.30   # gemini-3.5-flash-lite 유료 단가(ai.google.dev 가격표, 2026-10-01 갱신본)
GEMINI_OUTPUT_USD_PER_M = 2.50
COORD_CACHE = HERE / "answer_coords.json"


def norm(name: str) -> str:
    return re.sub(r"[\s\W_]+", "", (name or "").lower())


def haversine_m(a, b) -> float:
    lat1, lng1, lat2, lng2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lng2 - lng1) / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


# ---------- 정답 좌표 (카카오 주소/키워드 검색, 무료) ----------

def kakao(path: str, params: dict) -> dict:
    key = os.environ.get("KAKAO_REST_API_KEY")
    if not key:
        # 키 없이 돌면 정답 좌표가 전부 '없음'으로 캐시에 굳는다(2026-10-03 실제로 그랬다) — 저장하기 전에 멈춘다.
        sys.exit("KAKAO_REST_API_KEY가 없다 — 로컬 서버 .env를 불러온 뒤 채점한다.")
    url = "https://dapi.kakao.com" + path + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"Authorization": "KakaoAK " + key})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


def answer_coord(row: dict, cache: dict):
    """정답의 주소나 카카오맵 링크를 좌표로. 결과는 answer_coords.json에 저장해 다시 부르지 않는다(재현성)."""
    src = (row.get("address_or_kakao_url") or "").strip()
    if not src:
        return None
    if src in cache:
        return tuple(cache[src]) if cache[src] else None
    coord = None
    m = re.search(r"place\.map\.kakao\.com/(\d+)", src)
    if m:  # 장소 링크: 이름으로 찾아 같은 id를 고른다
        docs = kakao("/v2/local/search/keyword.json", {"query": row["place_name"], "size": 15}).get("documents", [])
        hit = next((d for d in docs if d.get("id") == m.group(1)), None)
        coord = (float(hit["y"]), float(hit["x"])) if hit else None
    else:
        docs = kakao("/v2/local/search/address.json", {"query": src}).get("documents", [])
        if not docs:
            docs = kakao("/v2/local/search/keyword.json", {"query": src}).get("documents", [])
        coord = (float(docs[0]["y"]), float(docs[0]["x"])) if docs else None
    cache[src] = list(coord) if coord else None
    return coord


# ---------- 입력 ----------

def load_answers() -> dict[int, list[dict]]:
    out: dict[int, list[dict]] = defaultdict(list)
    with open(HERE / "answers.csv", newline="") as f:
        for r in csv.DictReader(f):
            no = int(r["video_no"])
            out.setdefault(no, [])
            if r["place_name"].strip():
                out[no].append(r)
    return out


def load_manual() -> dict[tuple[int, str], dict]:
    path = HERE / "manual_matches.csv"
    if not path.exists():
        return {}
    with open(path, newline="") as f:
        return {(int(r["video_no"]), norm(r["result_name"])): r for r in csv.DictReader(f) if r["video_no"].strip()}


# ---------- 한 영상·한 회차 채점 ----------

def score_run(record: dict, answers: list[dict], manual: dict, coords: dict) -> dict:
    no = record["video_no"]
    results = record.get("places", [])
    ans_coord = {i: coords.get(i) for i in range(len(answers))}
    matched_answers: set[int] = set()
    ambiguous_answers: set[int] = set()
    rows = []
    for res in results:
        rname = res["name"]
        rc = (res["latitude"], res["longitude"]) if res.get("latitude") is not None else None
        man = manual.get((no, norm(rname)))
        if man:
            d = man["decision"].strip()
            if d == "match":
                idx = next((i for i, a in enumerate(answers) if norm(a["place_name"]) == norm(man["answer_place_name"])), None)
                if idx is not None:
                    matched_answers.add(idx)
                rows.append(_row(res, "맞음", answers[idx]["place_name"] if idx is not None else "", rc, ans_coord.get(idx), "수동"))
                continue
            if d == "false_positive":
                rows.append(_row(res, "오탐", "", rc, None, "수동", man.get("error_type") or "정답에 없는 장소"))
                continue
            rows.append(_row(res, "확인 필요", "", rc, None, "수동 보류"))
            continue

        exact = [i for i, a in enumerate(answers) if norm(a["place_name"]) == norm(rname)]
        partial = [i for i, a in enumerate(answers)
                   if i not in exact and len(norm(a["place_name"])) >= 2
                   and (norm(a["place_name"]) in norm(rname) or norm(rname) in norm(a["place_name"]))]
        if len(exact) == 1:
            i = exact[0]
            kind = "맞음" if answers[i].get("is_destination", "Y").strip().upper() != "N" else "오탐"
            if kind == "오탐":
                rows.append(_row(res, "오탐", answers[i]["place_name"], rc, ans_coord[i], "이름 같음", "경유지"))
            else:
                matched_answers.add(i)
                rows.append(_row(res, "맞음", answers[i]["place_name"], rc, ans_coord[i], "이름 같음"))
            continue
        cand = exact + partial
        if len(cand) == 1:
            i = cand[0]
            if answers[i].get("is_destination", "Y").strip().upper() == "N":
                rows.append(_row(res, "오탐", answers[i]["place_name"], rc, ans_coord[i], "이름 포함", "경유지"))
            elif rc and ans_coord[i] and haversine_m(rc, ans_coord[i]) <= COORD_MATCH_M:
                matched_answers.add(i)
                rows.append(_row(res, "맞음", answers[i]["place_name"], rc, ans_coord[i], "이름 포함+좌표 200m"))
            else:
                ambiguous_answers.add(i)
                rows.append(_row(res, "확인 필요", answers[i]["place_name"], rc, ans_coord[i],
                                 "이름 포함, 좌표로 확인 못 함" if not (rc and ans_coord[i]) else "이름 포함, 좌표 200m 밖(동명이소 의심)"))
            continue
        if len(cand) > 1:
            ambiguous_answers.update(cand)
            rows.append(_row(res, "확인 필요", " / ".join(answers[i]["place_name"] for i in cand), rc, None, "후보 여럿"))
            continue
        # 정답에 없는 결과 → 오탐 유형
        known = [c for c in ans_coord.values() if c]
        etype = "정답에 없는 장소"
        if rc and known and min(haversine_m(rc, c) for c in known) > OTHER_REGION_KM * 1000:
            etype = "다른 지역"
        rows.append(_row(res, "오탐", "", rc, None, "정답에 없음", etype))

    tp = sum(1 for r in rows if r["verdict"] == "맞음")
    fp = sum(1 for r in rows if r["verdict"] == "오탐")
    amb = sum(1 for r in rows if r["verdict"] == "확인 필요")
    dest_answers = [i for i, a in enumerate(answers) if a.get("is_destination", "Y").strip().upper() != "N"]
    missed = [i for i in dest_answers if i not in matched_answers and i not in ambiguous_answers]
    for i in missed:
        rows.append({"result_name": "", "verdict": "누락", "answer_place_name": answers[i]["place_name"],
                     "how": "", "error_type": "누락", "distance_m": None})
    coord_checked = [r for r in rows if r["verdict"] == "맞음" and r["distance_m"] is not None]
    coord_ok = sum(1 for r in coord_checked if r["distance_m"] <= COORD_MATCH_M)
    return {"video_no": no, "repeat": record["repeat"], "tp": tp, "fp": fp, "fn": len(missed), "ambiguous": amb,
            "coord_checked": len(coord_checked), "coord_ok": coord_ok, "rows": rows}


def _row(res, verdict, answer_name, rc, ac, how, error_type=""):
    dist = round(haversine_m(rc, ac)) if rc and ac else None
    if verdict == "맞음" and dist is not None and dist > COORD_MATCH_M:
        error_type = "이름 같은 다른 장소(좌표 200m 밖)"
    return {"result_name": res["name"], "verdict": verdict, "answer_place_name": answer_name, "how": how,
            "error_type": error_type, "distance_m": dist}


def ratio(a, b):
    return round(a / b, 3) if b else None


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("result_dir")
    ap.add_argument("--usd-krw", type=float, help="환율(원/달러) — 출처와 함께 줄 때만 원화 환산")
    ap.add_argument("--usd-krw-source", default="")
    args = ap.parse_args()
    rdir = Path(args.result_dir)
    answers = load_answers()
    manual = load_manual()
    cache = json.loads(COORD_CACHE.read_text()) if COORD_CACHE.exists() else {}

    per_run, review, failures = [], [], []
    tokens_in = tokens_out = gemini_calls = 0
    times = []
    for f in sorted((rdir / "raw").glob("*.json")):
        rec = json.loads(f.read_text())
        no = rec["video_no"]
        if no not in answers:
            print(f"영상 {no}: 정답 없음 — 채점에서 뺀다", file=sys.stderr)
            continue
        job = rec.get("job") or {}
        if rec.get("result") == "SUBMIT_FAILED" or job.get("status") != "DONE":
            failures.append({"video_no": no, "repeat": rec["repeat"],
                             "reason": (job.get("error_message") or rec.get("wait", {}).get("status") or rec.get("result") or "")[:200]})
        coords = {i: answer_coord(a, cache) for i, a in enumerate(answers[no])}
        s = score_run(rec, answers[no], manual, coords)
        per_run.append(s)
        for r in s["rows"]:
            if r["verdict"] == "확인 필요":
                review.append({"video_no": no, "repeat": rec["repeat"], **r})
        if rec.get("wall_seconds") is not None:
            times.append(rec["wall_seconds"])
        for c in rec.get("api_calls", []):
            if c["provider"] == "gemini":
                gemini_calls += 1
                tokens_in += c.get("prompt_tokens") or 0
                tokens_out += c.get("response_tokens") or 0
    COORD_CACHE.write_text(json.dumps(cache, ensure_ascii=False, indent=2))

    def agg(runs):
        tp, fp, fn = (sum(r[k] for r in runs) for k in ("tp", "fp", "fn"))
        p, rc = ratio(tp, tp + fp), ratio(tp, tp + fn)
        f1 = round(2 * p * rc / (p + rc), 3) if p and rc else None
        return {"tp": tp, "fp": fp, "fn": fn, "ambiguous": sum(r["ambiguous"] for r in runs),
                "precision": p, "recall": rc, "f1": f1,
                "coord_ok_ratio": ratio(sum(r["coord_ok"] for r in runs), sum(r["coord_checked"] for r in runs)),
                "coord_checked": sum(r["coord_checked"] for r in runs)}

    by_repeat = {rep: agg([r for r in per_run if r["repeat"] == rep]) for rep in sorted({r["repeat"] for r in per_run})}
    errors = Counter(row["error_type"] for r in per_run for row in r["rows"] if row["error_type"])
    paid_usd = tokens_in / 1e6 * GEMINI_INPUT_USD_PER_M + tokens_out / 1e6 * GEMINI_OUTPUT_USD_PER_M
    summary = {
        "overall": agg(per_run), "by_repeat": by_repeat, "error_types": dict(errors),
        "runs": len(per_run), "failures": failures,
        "seconds_per_video": {"median": sorted(times)[len(times) // 2] if times else None,
                              "max": max(times) if times else None, "n": len(times)},
        "gemini": {"calls": gemini_calls, "prompt_tokens": tokens_in, "response_tokens": tokens_out,
                   "actual_cost_krw": 0, "actual_cost_note": "Gemini 무료 티어 키 — 청구 없음",
                   "paid_equivalent_usd": round(paid_usd, 4),
                   "paid_equivalent_krw": round(paid_usd * args.usd_krw) if args.usd_krw else None,
                   "usd_krw": args.usd_krw, "usd_krw_source": args.usd_krw_source},
    }
    (rdir / "score.json").write_text(json.dumps({"summary": summary, "per_run": per_run}, ensure_ascii=False, indent=2))
    with open(rdir / "review_needed.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["video_no", "repeat", "result_name", "verdict", "answer_place_name", "how",
                                          "error_type", "distance_m"])
        w.writeheader()
        w.writerows(review)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
