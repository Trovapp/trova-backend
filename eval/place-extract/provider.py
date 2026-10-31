"""promptfoo exec 제공자 — 서비스의 실제 파이프라인(pipeline-test/run_pipeline.py)을 그대로 불러 장소 이름 배열을 돌려준다.

argv[1] = 프롬프트("URL:<링크>"). 출력은 JSON 배열(장소 이름)만. 진행 로그는 stderr로 나가므로 채점에 섞이지 않는다.
환경 변수 GEMINI_API_KEY는 evalflow 설정의 envFile(pipeline-test/.env)로 들어온다.
PIPELINE_FILTER_TEXT_ONLY=1 같은 실험 플래그도 환경 변수로 그대로 전달된다(변경 전후 비교용).
"""
from __future__ import annotations

import json
import os
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "pipeline-test"))

import run_pipeline  # noqa: E402


def main() -> None:
    prompt = sys.argv[1] if len(sys.argv) > 1 else ""
    url = prompt.split("URL:", 1)[-1].strip()
    if not url:
        raise SystemExit("URL이 없습니다")
    os.environ["PIPELINE_TRACE"] = "1"
    with tempfile.TemporaryDirectory(prefix="trova-eval-") as work:
        result = run_pipeline.run(url, Path(work))
    names = [re.sub(r"\s+", "", p.get("name") or "") for p in result.get("places", [])]
    print(json.dumps([n for n in names if n], ensure_ascii=False))


if __name__ == "__main__":
    main()
