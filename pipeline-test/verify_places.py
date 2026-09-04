#!/usr/bin/env python3
"""좌표가 불확실한(폴백 매칭이거나 원래 이름 확신도가 낮은) 장소들을,
Gemini에게 "이 지역에 실제 존재하는 장소인지" 한 번에 물어봐서 걸러낸다.

영상 하나당 검증이 필요한 장소를 한 번에 모아서 호출한다 — 장소마다 따로
호출하지 않는다(비용 절약).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from extract_places import DEFAULT_MODEL, call_gemini, load_api_key, _extract_text

VERIFY_PROMPT = """당신은 여행 영상에서 추출된 장소 이름이 실제로 존재하는 곳인지 엄격하게 검증하는 도구입니다.
아래 "검증 대상 목록"의 각 장소에 대해, name이라는 이름을 가진 곳이 **정확히 region으로 명시된
지역 안에** 실제로 존재하는지 판단하세요.

중요: name이 "실존하는 종류의 이름처럼 들리는가"가 아니라, "정확히 이 region 안에 그 이름의
장소가 실제로 있는가"를 판단하세요. 예를 들어 "청연로"라는 도로명이 한국 어딘가에 있다고 해도,
region이 "전주"인데 전주에는 "청연로"라는 도로가 없다면 그 조합은 valid: false입니다. 같은
이름이 다른 지역에 있는 것과, 검증 대상 region에 있는 것은 다릅니다. 확신이 안 서면
valid: false로 두세요(모르면 틀렸다고 간주하는 게 더 안전합니다).

각 항목은 다음 필드를 가집니다:
- index: 입력에 있던 index를 그대로 반환하세요 (정수)
- valid: name이 정확히 이 region 안에 실존하면 true, 아니면 false (boolean)

JSON 배열만 출력하세요. JSON 배열 외의 다른 텍스트는 출력하지 마세요.
"""


def _validate_verdicts(verdicts: list, expected_indices: set[int]) -> list[dict]:
    if len(verdicts) != len(expected_indices):
        raise SystemExit(
            f"검증 결과 개수가 입력과 다릅니다 (입력 {len(expected_indices)}건, 응답 {len(verdicts)}건)"
        )

    result_indices: set[int] = set()
    for item in verdicts:
        if not isinstance(item, dict):
            raise SystemExit(f"배열 항목이 객체가 아닙니다: {item!r}")
        idx = item.get("index")
        valid = item.get("valid")
        if not isinstance(idx, int) or isinstance(idx, bool):
            raise SystemExit(f"index가 정수가 아닙니다: {item!r}")
        if not isinstance(valid, bool):
            raise SystemExit(f"valid가 boolean이 아닙니다: {item!r}")
        result_indices.add(idx)

    if result_indices != expected_indices:
        missing = expected_indices - result_indices
        extra = result_indices - expected_indices
        raise SystemExit(f"검증 결과 index가 입력과 일치하지 않습니다 (누락: {missing}, 초과: {extra})")

    return verdicts


def verify_places(candidates: list[dict], model: str = DEFAULT_MODEL) -> list[dict]:
    if not candidates:
        return []
    api_key = load_api_key()
    parts = [
        {"text": f"검증 대상 목록: {json.dumps(candidates, ensure_ascii=False)}"},
        {"text": VERIFY_PROMPT},
    ]
    payload = call_gemini(parts, model, api_key, "verify_places")
    text = _extract_text(payload)
    try:
        verdicts = json.loads(text)
    except json.JSONDecodeError:
        raise SystemExit(f"Gemini did not return valid JSON: {text[:500]}")
    if not isinstance(verdicts, list):
        raise SystemExit(f"Gemini 응답이 배열이 아닙니다: {text[:500]}")

    expected_indices = {c["index"] for c in candidates}
    return _validate_verdicts(verdicts, expected_indices)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: verify_places.py <candidates.json>", file=sys.stderr)
        raise SystemExit(2)

    input_path = Path(sys.argv[1])
    if not input_path.exists():
        raise SystemExit(f"file not found: {input_path}")

    candidates = json.loads(input_path.read_text(encoding="utf-8"))
    verdicts = verify_places(candidates)
    print(json.dumps(verdicts, ensure_ascii=False, indent=2))
