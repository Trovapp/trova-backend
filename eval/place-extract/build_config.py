#!/usr/bin/env python3
"""eval/extraction의 사람이 적은 정답(videos.csv + answers.csv)에서 promptfoo 설정(promptfooconfig.yaml)의 tests를 만든다.

정답은 한 곳(answers.csv)에만 둔다 — 여기서는 옮겨 적지 않고 생성만 한다(ADR 0022). 정답을 고쳤으면 다시 돌린다:
    python3 eval/place-extract/build_config.py [--platform youtube_shorts|instagram_reel|all] [--limit N]
is_destination=N(경유지)인 정답은 기대 목록에서 뺀다(Part A 채점과 같은 기준 — 결과에 나오면 오탐).
"""
from __future__ import annotations

import argparse
import csv
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VIDEOS = ROOT / "eval" / "extraction" / "videos.csv"
ANSWERS = ROOT / "eval" / "extraction" / "answers.csv"
CONFIG = Path(__file__).resolve().parent / "promptfooconfig.yaml"
MARK = "tests:  # 아래는 build_config.py가 만든다 — 손으로 고치지 말 것\n"


def build_tests(platform: str, limit: int) -> list[dict]:
    answers: dict[str, list[dict]] = {}
    with ANSWERS.open(encoding="utf-8") as f:
        for row in csv.DictReader(f):
            answers.setdefault(row["video_no"], []).append(row)
    tests = []
    with VIDEOS.open(encoding="utf-8") as f:
        for video in csv.DictReader(f):
            if video["selected"] != "Y" or video["video_no"] not in answers:
                continue
            if platform != "all" and video["platform"] != platform:
                continue
            expected = [r["place_name"].strip() for r in answers[video["video_no"]]
                        if r["place_name"].strip() and r.get("is_destination", "Y").strip().upper() != "N"]
            tests.append({
                "description": f"{video['video_no']}-{video['platform']}-{video['title']}",
                "vars": {"url": video["url"], "expected": json.dumps(expected, ensure_ascii=False)},
            })
            if len(tests) >= limit:
                break
    return tests


def render(tests: list[dict]) -> str:
    lines = [MARK]
    for t in tests:
        lines.append(f"  - description: {json.dumps(t['description'], ensure_ascii=False)}\n")
        lines.append(f"    vars: {{url: {json.dumps(t['vars']['url'])}, expected: {json.dumps(t['vars']['expected'], ensure_ascii=False)}}}\n")
    return "".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", default="youtube_shorts", choices=["youtube_shorts", "instagram_reel", "all"])
    parser.add_argument("--limit", type=int, default=10)
    args = parser.parse_args()
    text = CONFIG.read_text(encoding="utf-8")
    head = re.split(r"^tests:.*\n", text, maxsplit=1, flags=re.M)[0]
    tests = build_tests(args.platform, args.limit)
    CONFIG.write_text(head + render(tests), encoding="utf-8")
    print(f"tests {len(tests)}개 → {CONFIG.relative_to(ROOT)} (정답 출처 {ANSWERS.relative_to(ROOT)})")


if __name__ == "__main__":
    main()
