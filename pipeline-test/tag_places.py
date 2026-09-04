#!/usr/bin/env python3
"""추천엔진 후보 장소들에 Gemini로 mood(분위기)/space(실내외) 태그를 붙인다.

네이버 리뷰 스크래핑 등 별도 텍스트 수집 없이, Google Places 자체 데이터(이름/카테고리/
평점/가격대)만으로 태깅한다 — 비공식 스크래핑에 의존하지 않기로 한 결정(0-1 참고).

영상 하나당(추천 요청 하나당) 후보를 한 번에 모아서 호출한다 — 후보마다 따로
호출하지 않는다(비용 절약, Trova의 낮은 Gemini RPM 한도에 맞춤).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from extract_places import DEFAULT_MODEL, call_gemini_with_repair, load_api_key

MOOD_VALUES = ["CALM", "LIVELY", "ROMANTIC", "TRENDY", "COZY", "LUXURIOUS"]
SPACE_VALUES = ["INDOOR", "OUTDOOR", "MIXED"]

TAG_PROMPT = f"""당신은 장소 이름/카테고리/평점/가격대만 보고 그 장소의 분위기(mood)와
실내외 여부(space)를 태깅하는 도구입니다. 리뷰 텍스트는 없으니, 주어진 정보만으로
가장 그럴듯하게 추론하세요.

mood는 다음 중 하나만 사용하세요: {", ".join(MOOD_VALUES)}
space는 다음 중 하나만 사용하세요: {", ".join(SPACE_VALUES)}

각 항목은 다음 필드를 가집니다:
- index: 입력에 있던 index를 그대로 반환하세요 (정수)
- mood: 위 목록 중 하나 (문자열)
- space: 위 목록 중 하나 (문자열)

JSON 배열만 출력하세요. JSON 배열 외의 다른 텍스트는 출력하지 마세요.
"""


def _validate_tags(tags: list, expected_indices: set[int]) -> list[dict]:
    if len(tags) != len(expected_indices):
        raise ValueError(
            f"태깅 결과 개수가 입력과 다릅니다 (입력 {len(expected_indices)}건, 응답 {len(tags)}건)"
        )

    result_indices: set[int] = set()
    for item in tags:
        if not isinstance(item, dict):
            raise ValueError(f"배열 항목이 객체가 아닙니다: {item!r}")
        idx = item.get("index")
        mood = item.get("mood")
        space = item.get("space")
        if not isinstance(idx, int) or isinstance(idx, bool):
            raise ValueError(f"index가 정수가 아닙니다: {item!r}")
        if mood not in MOOD_VALUES:
            raise ValueError(f"mood가 허용된 값이 아닙니다: {item!r}")
        if space not in SPACE_VALUES:
            raise ValueError(f"space가 허용된 값이 아닙니다: {item!r}")
        result_indices.add(idx)

    if result_indices != expected_indices:
        missing = expected_indices - result_indices
        extra = result_indices - expected_indices
        raise ValueError(f"태깅 결과 index가 입력과 일치하지 않습니다 (누락: {missing}, 초과: {extra})")

    return tags


def tag_places(candidates: list[dict], model: str = DEFAULT_MODEL) -> list[dict]:
    if not candidates:
        return []
    api_key = load_api_key()
    parts = [
        {"text": f"태깅 대상 목록: {json.dumps(candidates, ensure_ascii=False)}"},
        {"text": TAG_PROMPT},
    ]
    expected_indices = {c["index"] for c in candidates}

    def _parse(text: str) -> list[dict]:
        try:
            tags = json.loads(text)
        except json.JSONDecodeError:
            raise ValueError(f"유효한 JSON이 아님: {text[:500]}")
        if not isinstance(tags, list):
            raise ValueError(f"응답이 배열이 아님: {text[:500]}")
        return _validate_tags(tags, expected_indices)

    return call_gemini_with_repair(parts, model, api_key, "tag_places", _parse)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: tag_places.py <candidates.json>", file=sys.stderr)
        raise SystemExit(2)

    input_path = Path(sys.argv[1])
    if not input_path.exists():
        raise SystemExit(f"file not found: {input_path}")

    candidates = json.loads(input_path.read_text(encoding="utf-8"))
    tags = tag_places(candidates)
    print(json.dumps(tags, ensure_ascii=False, indent=2))
