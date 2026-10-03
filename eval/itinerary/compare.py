"""Part B 비교 — 지금 기능(run.py)과 일정 에이전트(run_agent.py)의 채점 결과(score.json)를 요청별로 나란히 놓는다.

둘 다 같은 채점기(score.py)·같은 요청·같은 영상 작업·같은 측정용 영업시간으로 잰 결과여야 한다.
사용: python eval/itinerary/compare.py <지금 기능 결과 폴더> <에이전트 결과 폴더>
결과: <에이전트 결과 폴더>/compare.json, compare.md(표)
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

RULES = ["영업", "이동", "분량", "없는장소", "기간", "지역"]
MARK = {"통과": "O", "위반": "X", "판정 불가": "?"}


def load(d: Path) -> dict:
    return json.loads((d / "score.json").read_text())


def main():
    base_dir, agent_dir = Path(sys.argv[1]), Path(sys.argv[2])
    base, agent = load(base_dir), load(agent_dir)
    b_per = {p["id"]: p for p in base["per_request"]}
    a_per = {p["id"]: p for p in agent["per_request"]}
    ids = [i for i in a_per if i in b_per]
    rows = []
    for i in ids:
        b, a = b_per[i], a_per[i]
        rows.append({
            "id": i,
            "base": {r: (b["rules"][r]["verdict"] if not b.get("failed") else "실패") for r in RULES},
            "agent": {r: (a["rules"][r]["verdict"] if not a.get("failed") else "실패") for r in RULES},
            "base_violations": b["violations"], "agent_violations": a["violations"],
            "base_unsupported": b["unsupported"],
        })
    bs, ag = base["summary"], agent["summary"]
    summary = {
        "requests_compared": len(ids),
        "all_rules_pass": {"base": bs["all_rules_pass"], "agent": ag["all_rules_pass"]},
        "no_violation": {"base": bs["no_violation_with_unknowns"]["count"], "agent": ag["no_violation_with_unknowns"]["count"]},
        "violations_by_rule": {"base": bs["violations_by_rule"], "agent": ag["violations_by_rule"]},
        "unknown_by_rule": {"base": bs["unknown_by_rule"], "agent": ag["unknown_by_rule"]},
        "unsupported_conditions": {"base": bs["unsupported_conditions"], "agent": ag["unsupported_conditions"]},
        "seconds": {"base": bs["seconds_per_generation"], "agent": ag["seconds_per_generation"]},
        "gemini": {"base": bs["gemini"], "agent": ag["gemini"]},
        "failed": {"base": bs["failed"], "agent": ag["failed"]},
    }
    (agent_dir / "compare.json").write_text(json.dumps({"base_dir": str(base_dir), "agent_dir": str(agent_dir),
                                                        "summary": summary, "rows": rows}, ensure_ascii=False, indent=1))
    lines = ["| 요청 | " + " | ".join(f"{r} 지금/에이전트" for r in RULES) + " | 위반 수 지금→에이전트 |",
             "|---|" + "---|" * (len(RULES) + 1)]
    for row in rows:
        cells = [f"{MARK.get(row['base'][r], row['base'][r])}/{MARK.get(row['agent'][r], row['agent'][r])}" for r in RULES]
        lines.append(f"| {row['id']} | " + " | ".join(cells) + f" | {row['base_violations']}→{row['agent_violations']} |")
    (agent_dir / "compare.md").write_text("\n".join(lines) + "\n\nO 통과 · X 위반 · ? 판정 불가\n")
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    print("\n".join(lines))


if __name__ == "__main__":
    main()
