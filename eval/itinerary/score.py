"""Part B 채점 — 코드 규칙으로만 판정한다(AI 판정 없음). 규칙마다 통과 / 위반 / 판정 불가.

규칙(상수는 2026-10-03 사용자 승인값)
  영업      방문일이 그 장소의 휴무 요일이면 위반. 날짜나 영업시간이 없으면 판정 불가(방문 시각이 없어 '영업시간 밖'은 늘 판정 불가)
  이동      같은 날 연속한 두 장소의 직선거리 > 30km면 위반. 좌표 없는 쌍은 판정 불가
  분량      하루 장소 수가 2~7곳 밖이면 위반. 입력 장소가 1곳이면 판정 불가
  없는장소  입력에 없던 장소가 일정에 있으면 위반(개수 집계)
  기간      일정 일수(영상별 최대 일차)가 요청 일수와 다르면 위반
  지역      요청 영상들의 장소 중심점 어디에서도 50km 밖인 장소가 있으면 위반
  반영불가  지금 API에 전달할 수 없는 조건(일수 지정, 여러 영상)을 따로 센다 — 통과/위반과 별개(에이전트 기록은 없음)

사용: python eval/itinerary/score.py <결과 폴더>
"""
from __future__ import annotations

import datetime as dt
import json
import math
import sys
from collections import Counter
from pathlib import Path

MAX_HOP_KM = 30
DAY_MIN, DAY_MAX = 2, 7
REGION_KM = 50
GEMINI_INPUT_USD_PER_M, GEMINI_OUTPUT_USD_PER_M = 0.30, 2.50
RULES = ["영업", "이동", "분량", "없는장소", "기간", "지역"]


def km(a, b):
    lat1, lng1, lat2, lng2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lng2 - lng1) / 2) ** 2
    return 2 * 6371 * math.asin(math.sqrt(h))


def coord(p):
    return (p["latitude"], p["longitude"]) if p.get("latitude") is not None else None


def open_on(periods, date):
    if not periods:
        return None
    gday = (date.weekday() + 1) % 7
    if any("close" not in p for p in periods):
        return True
    return any(p.get("open", {}).get("day") == gday for p in periods)


def judge(rec: dict) -> dict:
    req = rec["request"]
    res = {r: {"verdict": "판정 불가", "detail": []} for r in RULES}
    videos = rec.get("videos", [])
    if not videos or any(v["generate"].get("result") != "DONE" for v in videos):
        return {"id": req["id"], "rules": res, "failed": rec.get("result") or [v["generate"] for v in videos],
                "unsupported": [], "violations": 0}

    # 에이전트(run_agent.py)는 일수·여러 영상을 실제로 받는다 — 반영 불가 조건이 없다.
    unsupported = [] if rec.get("mode") == "agent" else ["일수 지정"] + (["여러 영상"] if len(req["videos"]) > 1 else [])
    start = dt.date.fromisoformat(rec["start_date"]) if rec.get("start_date") else None
    hours = rec.get("hours", {})

    # 영업
    viol, unknown = [], 0
    for v in videos:
        for p in v["places"]:
            if not start or p["day"] is None:
                unknown += 1
                continue
            o = open_on((hours.get(str(p["id"])) or {}).get("periods"), start + dt.timedelta(days=p["day"] - 1))
            if o is None:
                unknown += 1
            elif o is False:
                viol.append(f"{p['name']} {p['day']}일차 휴무")
    res["영업"] = {"verdict": "위반" if viol else ("판정 불가" if unknown else "통과"), "detail": viol,
                 "unknown_places": unknown}

    # 이동 · 분량
    hop_viol, hop_unknown, load_viol = [], 0, []
    total_input = sum(len(v["input_place_ids"]) for v in videos)
    for v in videos:
        days = {}
        for p in v["places"]:
            days.setdefault(p["day"], []).append(p)
        for d, ps in days.items():
            ps.sort(key=lambda p: (p["order"] is None, p["order"]))
            if not (DAY_MIN <= len(ps) <= DAY_MAX):
                load_viol.append(f"영상{v['video_no']} {d}일차 {len(ps)}곳")
            for a, b in zip(ps, ps[1:]):
                ca, cb = coord(a), coord(b)
                if not (ca and cb):
                    hop_unknown += 1
                elif km(ca, cb) > MAX_HOP_KM:
                    hop_viol.append(f"{a['name']}→{b['name']} {km(ca, cb):.0f}km")
    res["이동"] = {"verdict": "위반" if hop_viol else ("판정 불가" if hop_unknown else "통과"), "detail": hop_viol,
                 "unknown_pairs": hop_unknown}
    res["분량"] = {"verdict": "판정 불가" if total_input <= 1 else ("위반" if load_viol else "통과"), "detail": load_viol}

    # 없는 장소
    extra = [p["name"] for v in videos for p in v["places"] if p["id"] not in set(v["input_place_ids"])]
    res["없는장소"] = {"verdict": "위반" if extra else "통과", "detail": extra, "count": len(extra)}

    # 기간
    got = max((p["day"] or 0) for v in videos for p in v["places"]) if any(v["places"] for v in videos) else 0
    res["기간"] = {"verdict": "통과" if got == req["days"] else "위반", "detail": [f"요청 {req['days']}일, 일정 {got}일"]}

    # 지역
    centers = []
    for v in videos:
        # 중심은 요청 영상의 입력 장소로만 잡는다 — 일정에 끼어든 장소가 중심을 끌고 가면 정상 장소까지 위반이 된다.
        inputs = set(v["input_place_ids"])
        cs = [coord(p) for p in v["places"] if coord(p) and p["id"] in inputs]
        if cs:
            centers.append((sum(c[0] for c in cs) / len(cs), sum(c[1] for c in cs) / len(cs)))
    far = [p["name"] for v in videos for p in v["places"]
           if coord(p) and centers and min(km(coord(p), c) for c in centers) > REGION_KM]
    res["지역"] = {"verdict": "판정 불가" if not centers else ("위반" if far else "통과"), "detail": far}

    return {"id": req["id"], "rules": res, "unsupported": unsupported,
            "violations": sum(1 for r in res.values() if r["verdict"] == "위반")}


def main():
    rdir = Path(sys.argv[1])
    per = []
    secs, tin, tout = [], 0, 0
    for f in sorted((rdir / "raw").glob("B*.json"), key=lambda p: int(p.stem[1:])):
        rec = json.loads(f.read_text())
        per.append(judge(rec))
        for v in rec.get("videos", []):
            if v["generate"].get("seconds") is not None:
                secs.append(v["generate"]["seconds"])
            for c in v.get("gemini", []):
                tin += c.get("prompt_tokens") or 0
                tout += c.get("response_tokens") or 0
    judged = [p for p in per if not p.get("failed")]
    all_pass = [p["id"] for p in judged if all(r["verdict"] == "통과" for r in p["rules"].values())]
    no_violation = [p["id"] for p in judged if p["violations"] == 0]
    summary = {
        "requests": len(per), "judged": len(judged), "failed": [p["id"] for p in per if p.get("failed")],
        "all_rules_pass": {"count": len(all_pass), "ratio": round(len(all_pass) / len(judged), 3) if judged else None,
                           "ids": all_pass},
        "no_violation_with_unknowns": {"count": len(no_violation), "ids": no_violation},
        "violations_by_rule": {r: sum(1 for p in judged if p["rules"][r]["verdict"] == "위반") for r in RULES},
        "unknown_by_rule": {r: sum(1 for p in judged if p["rules"][r]["verdict"] == "판정 불가") for r in RULES},
        "unsupported_conditions": dict(Counter(u for p in judged for u in p["unsupported"])),
        "seconds_per_generation": {"median": sorted(secs)[len(secs) // 2] if secs else None,
                                   "max": max(secs) if secs else None, "n": len(secs)},
        "gemini": {"prompt_tokens": tin, "response_tokens": tout, "actual_cost_krw": 0,
                   "paid_equivalent_usd": round(tin / 1e6 * GEMINI_INPUT_USD_PER_M + tout / 1e6 * GEMINI_OUTPUT_USD_PER_M, 4)},
    }
    (rdir / "score.json").write_text(json.dumps({"summary": summary, "per_request": per}, ensure_ascii=False, indent=1))
    print(json.dumps(summary, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
