"""에이전트가 뺀 장소 중 '사실로 확인되는 이유 없이' 뺀 것을 센다(#108 전후 비교용). 서버 코드와 따로 같은 기준을 다시 구현했다.

뺀 장소가 아래에 모두 해당하면 "넣을 수 있었던 제외"로 센다:
  좌표가 있다 · 공항/역/터미널 같은 지나가는 곳이 아니다 · 일정에 넣은 곳과 150m 넘게(중복 아님), 가장 가까운 곳과 30km 안
  · 하루 7곳 미만인 날 중 그날 휴무가 아니고(영업시간 모르면 휴무 아님으로 봄), 가장 덜 돌아가는 자리에 넣어도 연속 이동이 모두 30km 안인 날이 있다
시각(21시)과 영업시간 밖은 보지 않는다 — 이 지표는 위 조건만으로 센 상한이다.

사용: python eval/itinerary/excluded_check.py <에이전트 결과 폴더> [...]
"""
from __future__ import annotations

import datetime as dt
import json
import math
import re
import sys
from pathlib import Path

DUP_M, MAX_HOP_KM, DAY_MAX = 150, 30, 7
TRANSIT = re.compile(r"(공항|역|터미널|정류장|정류소|휴게소|IC)$")


def km(a, b):
    lat1, lng1, lat2, lng2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lng2 - lng1) / 2) ** 2
    return 2 * 6371 * math.asin(math.sqrt(h))


def open_on(periods, date):
    if not periods or date is None:
        return None
    g = (date.weekday() + 1) % 7
    if any("close" not in p for p in periods):
        return True
    return any(p.get("open", {}).get("day") == g for p in periods)


def path_km(cs):
    return sum(km(a, b) for a, b in zip(cs, cs[1:]))


def check(rec: dict) -> dict:
    plan = (rec.get("agent") or {}).get("draft") or {}
    inputs = {p["id"]: p for p in rec.get("inputs", [])}
    start = dt.date.fromisoformat(rec["start_date"]) if rec.get("start_date") else None
    hours = rec.get("hours", {})
    days = [[inputs.get(it["placeId"]) for it in d["items"]] for d in plan.get("days", [])]
    scheduled = [p for d in days for p in d if p and p.get("latitude") is not None]
    out = []
    for e in plan.get("excluded", []):
        p = inputs.get(e["placeId"])
        if not p or p.get("latitude") is None or TRANSIT.search((p["name"] or "").replace(" ", "")):
            continue
        c = (p["latitude"], p["longitude"])
        near = min((km(c, (q["latitude"], q["longitude"])) for q in scheduled), default=None)
        if near is not None and (near * 1000 <= DUP_M or near > MAX_HOP_KM):
            continue
        fits = []
        for i, d in enumerate(days):
            if len(d) >= DAY_MAX:
                continue
            date = start + dt.timedelta(days=i) if start else None
            if open_on((hours.get(str(p["id"])) or {}).get("periods"), date) is False:
                continue
            cs = [(q["latitude"], q["longitude"]) for q in d if q and q.get("latitude") is not None]
            best = min((cs[:k] + [c] + cs[k:] for k in range(len(cs) + 1)), key=path_km)
            if all(km(a, b) <= MAX_HOP_KM for a, b in zip(best, best[1:])):
                fits.append(i + 1)
        if fits:
            out.append({"name": p["name"], "reason": e["reason"], "fits_days": fits})
    return {"id": rec["request"]["id"], "excluded": len(plan.get("excluded", [])), "restorable": out,
            "scheduled": sum(len(d) for d in days)}


def main():
    for d in sys.argv[1:]:
        rows = [check(json.loads(f.read_text())) for f in sorted(Path(d, "raw").glob("B*.json"), key=lambda f: int(f.stem[1:]))]
        summary = {"excluded": sum(r["excluded"] for r in rows), "restorable": sum(len(r["restorable"]) for r in rows),
                   "scheduled": sum(r["scheduled"] for r in rows),
                   "requests_with_restorable": [r["id"] for r in rows if r["restorable"]]}
        Path(d, "excluded_check.json").write_text(json.dumps({"summary": summary, "rows": rows}, ensure_ascii=False, indent=1))
        print(d, json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
