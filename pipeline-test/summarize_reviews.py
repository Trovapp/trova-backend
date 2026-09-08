#!/usr/bin/env python3
"""장소 리뷰 텍스트 목록을 Gemini로 구조화된 요약(장단점/시간·요금/꿀팁/체크리스트)으로 정리한다.

리뷰 하나하나를 따로 호출하지 않고, 한 장소의 리뷰 전체를 한 번에 모아 호출한다
(비용 절약, Trova의 낮은 Gemini RPM 한도에 맞춤 — tag_places.py와 동일한 원칙).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from extract_places import DEFAULT_MODEL, call_gemini_with_repair, load_api_key

SUMMARY_PROMPT = """당신은 장소 리뷰들을 분석해서 여행자에게 실용적인 정보를 구조화해서
제공하는 도구입니다. 리뷰에 실제로 있는 내용만 쓰고, 없는 정보를 지어내지 마세요.
리뷰가 영어 등 다른 언어로 되어 있어도 모든 텍스트는 반드시 한국어로 작성하세요.

다음 형식의 JSON 객체만 출력하세요. 다른 텍스트는 출력하지 마세요.
{
  "highlights": "이 장소의 분위기·경치 등 매력을 감성적으로 담은 1~2문장 요약. 그중 가장 인상적인 키워드나 문구 1~2개는 **이렇게** 마크다운 굵게 표시로 감싸서 강조하세요.",
  "pros": ["리뷰에 나온 좋은 점", "..."],
  "cons": ["리뷰에 나온 아쉬운 점", "..."],
  "hours": "리뷰에서 언급된 운영시간을 종합한 문장, 언급이 없으면 null",
  "fee": "리뷰에서 언급된 요금/입장료를 종합한 문장, 언급이 없으면 null",
  "tips": ["방문 전 알아두면 좋은 꿀팁", "..."],
  "checklist": ["방문 전 확인하면 좋을 것", "..."]
}

pros/cons/tips/checklist는 각각 최대 4개까지만 담고, 리뷰에 근거가 없으면 빈 배열([])로
두세요. hours/fee는 리뷰에 명시적인 언급이 없으면 반드시 null로 두고 추측하지 마세요.
"""


def _validate_string_list(payload: dict, field: str) -> list:
    value = payload.get(field)
    if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
        raise ValueError(f"{field} 필드가 문자열 배열이 아닙니다: {payload!r}")
    return value


def _validate_nullable_string(payload: dict, field: str) -> None:
    value = payload.get(field)
    if value is not None and not isinstance(value, str):
        raise ValueError(f"{field} 필드가 문자열도 null도 아닙니다: {payload!r}")


def _validate_summary(payload) -> dict:
    if not isinstance(payload, dict):
        raise ValueError(f"응답이 객체가 아님: {payload!r}")

    highlights = payload.get("highlights")
    if not isinstance(highlights, str) or not highlights.strip():
        raise ValueError(f"highlights 필드가 비어있거나 없습니다: {payload!r}")

    for field in ("pros", "cons", "tips", "checklist"):
        _validate_string_list(payload, field)
    for field in ("hours", "fee"):
        _validate_nullable_string(payload, field)

    return payload


def summarize_reviews(review_texts: list[str], model: str = DEFAULT_MODEL) -> dict:
    if not review_texts:
        raise ValueError("review_texts가 비어있습니다")
    api_key = load_api_key()
    parts = [
        {"text": f"리뷰 목록: {json.dumps(review_texts, ensure_ascii=False)}"},
        {"text": SUMMARY_PROMPT},
    ]

    def _parse(text: str) -> dict:
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            raise ValueError(f"유효한 JSON이 아님: {text[:500]}")
        return _validate_summary(payload)

    return call_gemini_with_repair(parts, model, api_key, "summarize_reviews", _parse)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: summarize_reviews.py <reviews.json>", file=sys.stderr)
        raise SystemExit(2)

    input_path = Path(sys.argv[1])
    if not input_path.exists():
        raise SystemExit(f"file not found: {input_path}")

    review_texts = json.loads(input_path.read_text(encoding="utf-8"))
    result = summarize_reviews(review_texts)
    print(json.dumps(result, ensure_ascii=False, indent=2))
