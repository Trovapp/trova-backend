#!/usr/bin/env python3
"""장소 목록을 받아 Gemini로 며칠짜리 일정(day 배정 + 하루 안 순서)을 짠다.

extract_places.py의 Gemini 호출/재시도 로직을 그대로 재사용한다.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from extract_places import DEFAULT_MODEL, call_gemini, load_api_key, _extract_text

ITINERARY_PROMPT = """당신은 여러 장소를 며칠짜리 여행 일정으로 묶어주는 도구입니다.
아래 "장소 목록"의 각 장소를 하루 단위(day)로 묶고, 하루 안에서 방문 순서를 정하세요.

원칙:
- 같은 지역/동네에 있는 장소는 같은 날에 묶으세요.
- 하루에 방문하기 그럴듯한 개수(보통 2~6곳)로 나누세요. 장소가 아주 적으면 하루로 묶어도 됩니다.
- 방문 순서는 실제 이동 동선이 자연스럽게 이어지도록 정하세요 (카페→식당→관광지처럼 뒤죽박죽 오가지 않게).
- 입력에 있는 장소를 빠짐없이 전부 포함하세요. 장소를 추가하거나 빼지 마세요.

각 항목은 다음 필드를 가집니다:
- id: 입력에 있던 장소의 id를 그대로 반환하세요 (정수)
- dayNumber: 몇 일차인지 (1부터 시작하는 정수)
- orderInDay: 그 날 안에서의 방문 순서 (1부터 시작하는 정수)

JSON 배열만 출력하세요. JSON 배열 외의 다른 텍스트는 출력하지 마세요.
"""


def _validate_assignments(assignments: list, expected_ids: set[int]) -> list:
    if len(assignments) != len(expected_ids):
        raise SystemExit(
            f"일정 항목 개수가 입력과 일치하지 않습니다 "
            f"(입력: {len(expected_ids)}개, 출력: {len(assignments)}개). "
            f"중복 id나 누락이 있을 수 있습니다."
        )
    result_ids: set[int] = set()
    for item in assignments:
        if not isinstance(item, dict):
            raise SystemExit(f"배열 항목이 객체가 아닙니다: {item!r}")
        place_id = item.get("id")
        day_number = item.get("dayNumber")
        order_in_day = item.get("orderInDay")
        if not isinstance(place_id, int) or isinstance(place_id, bool):
            raise SystemExit(f"id가 정수가 아닙니다: {item!r}")
        if not isinstance(day_number, int) or isinstance(day_number, bool) or day_number < 1:
            raise SystemExit(f"dayNumber가 1 이상의 정수가 아닙니다: {item!r}")
        if not isinstance(order_in_day, int) or isinstance(order_in_day, bool) or order_in_day < 1:
            raise SystemExit(f"orderInDay가 1 이상의 정수가 아닙니다: {item!r}")
        result_ids.add(place_id)
    if result_ids != expected_ids:
        missing = expected_ids - result_ids
        extra = result_ids - expected_ids
        raise SystemExit(f"장소 id가 입력과 일치하지 않습니다 (누락: {missing}, 초과: {extra})")
    return assignments


def generate_itinerary(places: list[dict], model: str = DEFAULT_MODEL) -> list[dict]:
    if not places:
        raise ValueError("places는 비어 있을 수 없습니다")
    api_key = load_api_key()
    parts = [
        {"text": f"장소 목록: {json.dumps(places, ensure_ascii=False)}"},
        {"text": ITINERARY_PROMPT},
    ]
    payload = call_gemini(parts, model, api_key, "generate_itinerary")
    text = _extract_text(payload)
    try:
        assignments = json.loads(text)
    except json.JSONDecodeError:
        raise SystemExit(f"Gemini did not return valid JSON: {text[:500]}")
    if not isinstance(assignments, list):
        raise SystemExit(f"Gemini 응답이 배열이 아닙니다: {text[:500]}")

    expected_ids = {p["id"] for p in places}
    return _validate_assignments(assignments, expected_ids)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: generate_itinerary.py <places.json>", file=sys.stderr)
        raise SystemExit(2)

    input_path = Path(sys.argv[1])
    if not input_path.exists():
        raise SystemExit(f"file not found: {input_path}")

    places = json.loads(input_path.read_text(encoding="utf-8"))
    assignments = generate_itinerary(places)
    print(json.dumps(assignments, ensure_ascii=False, indent=2))
