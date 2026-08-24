#!/usr/bin/env python3
"""카카오 키워드 검색이 후보를 여러 개 반환했을 때, 1등을 무조건 채택하지 않고
Gemini에게 "영상 맥락(원래 인식된 이름 + 지역)에 가장 맞는 후보가 뭔지" 물어봐서
고른다.

영상 하나당 선택이 필요한 장소를 한 번에 모아서 호출한다 — 장소마다 따로
호출하지 않는다(비용 절약).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from extract_places import DEFAULT_MODEL, call_gemini, load_api_key, _extract_text

SELECT_PROMPT = """당신은 여행 영상에서 인식된 장소 이름에 대해, 카카오 지도 검색이 반환한 여러
후보 중 실제로 그 영상에서 언급된 곳이 어느 것인지 고르는 도구입니다.

아래 "선택 대상 목록"의 각 항목은 extractedName(영상에서 인식된 원래 이름), region(지역),
candidates(카카오 검색 결과 후보 목록, candidateIndex 0이 카카오 검색에서 가장 관련성
높다고 판단한 기본값)로 구성됩니다. 각 항목에 대해, extractedName과 region을 고려했을 때
candidates 중 어느 게 실제로 그 장소를 가리키는지 판단하세요.

원칙:
- candidateIndex 0(기본값)이 맞다고 판단되면 그대로 0을 반환하세요. 확신이 안 서면
  굳이 다른 후보로 바꾸지 말고 0을 유지하는 게 안전합니다(카카오 자체 랭킹이 이미
  관련성을 반영함).
- candidates 중 어느 것도 extractedName과 명백히 안 맞으면(예: 완전히 다른 지역이거나
  이름이 전혀 다른 것들뿐이면) selectedCandidateIndex를 null로 반환하세요.

각 항목은 다음 필드를 가집니다:
- index: 입력에 있던 index를 그대로 반환하세요 (정수)
- selectedCandidateIndex: 선택한 candidateIndex (정수) 또는 아무것도 안 맞으면 null

JSON 배열만 출력하세요. JSON 배열 외의 다른 텍스트는 출력하지 마세요.
"""


def _validate_selections(selections: list, expected_indices: set[int]) -> list[dict]:
    if len(selections) != len(expected_indices):
        raise SystemExit(
            f"선택 결과 개수가 입력과 다릅니다 (입력 {len(expected_indices)}건, 응답 {len(selections)}건)"
        )

    result_indices: set[int] = set()
    for item in selections:
        if not isinstance(item, dict):
            raise SystemExit(f"배열 항목이 객체가 아닙니다: {item!r}")
        idx = item.get("index")
        if not isinstance(idx, int) or isinstance(idx, bool):
            raise SystemExit(f"index가 정수가 아닙니다: {item!r}")
        if "selectedCandidateIndex" not in item:
            raise SystemExit(f"selectedCandidateIndex가 없습니다: {item!r}")
        selected = item.get("selectedCandidateIndex")
        if selected is not None and (not isinstance(selected, int) or isinstance(selected, bool)):
            raise SystemExit(f"selectedCandidateIndex가 정수도 null도 아닙니다: {item!r}")
        result_indices.add(idx)

    if result_indices != expected_indices:
        missing = expected_indices - result_indices
        extra = result_indices - expected_indices
        raise SystemExit(f"선택 결과 index가 입력과 일치하지 않습니다 (누락: {missing}, 초과: {extra})")

    return selections


def select_place_match(candidates: list[dict], model: str = DEFAULT_MODEL) -> list[dict]:
    if not candidates:
        return []
    api_key = load_api_key()
    parts = [
        {"text": f"선택 대상 목록: {json.dumps(candidates, ensure_ascii=False)}"},
        {"text": SELECT_PROMPT},
    ]
    payload = call_gemini(parts, model, api_key)
    text = _extract_text(payload)
    try:
        selections = json.loads(text)
    except json.JSONDecodeError:
        raise SystemExit(f"Gemini did not return valid JSON: {text[:500]}")
    if not isinstance(selections, list):
        raise SystemExit(f"Gemini 응답이 배열이 아닙니다: {text[:500]}")

    expected_indices = {c["index"] for c in candidates}
    return _validate_selections(selections, expected_indices)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: select_place_match.py <candidates.json>", file=sys.stderr)
        raise SystemExit(2)

    input_path = Path(sys.argv[1])
    if not input_path.exists():
        raise SystemExit(f"file not found: {input_path}")

    candidates = json.loads(input_path.read_text(encoding="utf-8"))
    selections = select_place_match(candidates)
    print(json.dumps(selections, ensure_ascii=False, indent=2))
