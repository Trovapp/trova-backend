#!/usr/bin/env python3
"""장소 리뷰 텍스트 목록을 Gemini로 2~3문장 요약한다.

리뷰 하나하나를 따로 호출하지 않고, 한 장소의 리뷰 전체를 한 번에 모아 호출한다
(비용 절약, Trova의 낮은 Gemini RPM 한도에 맞춤 — tag_places.py와 동일한 원칙).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from extract_places import DEFAULT_MODEL, call_gemini_with_repair, load_api_key

SUMMARY_PROMPT = """당신은 장소 리뷰들을 읽고 핵심을 2~3문장으로 요약하는 도구입니다.
장점/단점이 갈리면 균형 있게 담으세요. 과장하지 말고 리뷰에 실제로 있는 내용만 쓰세요.

다음 형식의 JSON 객체만 출력하세요. 다른 텍스트는 출력하지 마세요.
{"summary": "..."}
"""


def _validate_summary(payload) -> dict:
    if not isinstance(payload, dict):
        raise ValueError(f"응답이 객체가 아님: {payload!r}")
    summary = payload.get("summary")
    if not isinstance(summary, str) or not summary.strip():
        raise ValueError(f"summary 필드가 비어있거나 없습니다: {payload!r}")
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
