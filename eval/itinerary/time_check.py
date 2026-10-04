"""에이전트 초안의 시간대 문제를 센다(#112 전후 비교용).

  저녁 중복: 같은 날 저녁 칸(17~20시)에 시작하는 식당이 2곳 이상
  점심 중복: 같은 날 점심 칸(11~14시)에 시작하는 식당이 2곳 이상
  어두운 뒤 관광지: 18시 넘어 끝나는 관광지(attraction) — 전체와, 그중 코드가 되살린 것("다시 넣었어요")

사용: python eval/itinerary/time_check.py <에이전트 결과 폴더> [...]
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path


def mins(t: str) -> int:
    h, m = t.split(":")[:2]
    return int(h) * 60 + int(m)


def check(rec: dict) -> dict:
    a = rec.get("agent") or {}
    plan = a.get("draft") or {}
    # 서버 문장의 조사가 받침에 따라 은/는(한글이 아니면 "은(는)")으로 바뀌었다(#112 W3) — 셋 다 알아본다.
    restored = {m.group(1) for f in (a.get("fixes") or [])
                if (m := re.match(r"^(.*?)(?:은\(는\)|은|는) \d+일차에 자리가 있어 다시 넣었어요", f))}
    out = {"id": rec["request"]["id"], "dinner_dup": [], "lunch_dup": [], "dark_attraction": [], "dark_restored": []}
    for d in plan.get("days", []):
        items = d["items"]
        dinners = [i["name"] for i in items if i["category"] == "restaurant" and 17 * 60 <= mins(i["start"]) < 20 * 60]
        lunches = [i["name"] for i in items if i["category"] == "restaurant" and 11 * 60 <= mins(i["start"]) < 14 * 60]
        if len(dinners) > 1:
            out["dinner_dup"].append(f"{d['day']}일차 {dinners}")
        if len(lunches) > 1:
            out["lunch_dup"].append(f"{d['day']}일차 {lunches}")
        for i in items:
            if i["category"] == "attraction" and mins(i["end"]) > 18 * 60:
                out["dark_attraction"].append(f"{d['day']}일차 {i['name']} ~{i['end']}")
                if i["name"] in restored:
                    out["dark_restored"].append(f"{d['day']}일차 {i['name']} ~{i['end']}")
    return out


def main():
    for d in sys.argv[1:]:
        rows = [check(json.loads(f.read_text())) for f in sorted(Path(d, "raw").glob("B*.json"), key=lambda f: int(f.stem[1:]))]
        summary = {k: sum(len(r[k]) for r in rows) for k in ("dinner_dup", "lunch_dup", "dark_attraction", "dark_restored")}
        Path(d, "time_check.json").write_text(json.dumps({"summary": summary, "rows": rows}, ensure_ascii=False, indent=1))
        print(Path(d).name, json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
