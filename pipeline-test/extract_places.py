#!/usr/bin/env python3
"""자막 텍스트 또는 오디오 파일에서 Gemini API로 장소 정보를 추출한다.

Pure stdlib (urllib) — google-generativeai SDK 설치 없이 REST 직접 호출.
"""
from __future__ import annotations

import base64
import json
import mimetypes
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_MODEL = os.environ.get("GEMINI_MODEL", "gemini-3.5-flash-lite")
API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"

COLLECT_PROMPT = """당신은 여행 영상 자막/음성/화면 텍스트에서 장소일 가능성이 있는
고유명사를 하나도 놓치지 않고 나열하는 도구입니다. 아직 최종 정리 단계가 아니니
과감하게, 후하게 뽑으세요 — 스쳐 지나가듯 짧게 언급된 것, 확실하지 않은 것,
나중에 걸러질 것 같은 것도 전부 포함하세요. 행정구역명(서울/부산 등)인지 동네인지
상호명인지 구분하지 말고, 장소처럼 들리거나 화면에 보이는 고유명사는 전부 후보로
넣으세요. 같은 곳이 여러 번 언급돼도 중복 없이 한 번만 넣으세요.

JSON 배열(문자열 목록)만 출력하세요. 예: ["부산", "해운대", "서면", "황금집"]
후보가 전혀 없으면 빈 배열 []을 반환하세요. JSON 배열 외의 다른 텍스트는 출력하지 마세요.
"""

FILTER_PROMPT = """당신은 여행 영상 자막/음성/화면 텍스트에서 지도에 저장할 만한 구체적인 장소를 추출하는 도구입니다.

포함 기준:
- 식당, 카페, 관광지, 숙소, 상점 등 지도에서 검색해 갈 수 있는 구체적 지점(동네, 랜드마크, 상호명 포함)
- 오디오에서 스쳐 지나가듯 짧게 언급된 곳도 놓치지 말고 전부 포함하세요
- 화면에 찍힌 위치 태그/캡션도 반드시 확인해서 포함하세요
- 아래 "1차 후보 목록"에 있는 이름은 누락 없이 전부 검토하세요 — 포함/제외 기준에
  따라 최종적으로 빠지는 건 괜찮지만, 검토 자체를 건너뛰지 마세요

제외 기준:
- 시/도/광역시/특별시 등 행정구역 단위의 넓은 지역명 자체(예: "서울", "부산", "제주도")는 name으로 쓰지 마세요.
  다른 지역과 비교하거나 배경 설명으로만 언급된 경우가 많고, 지도에 찍을 지점이 아닙니다.
  단, "해운대"처럼 그 안의 동네/랜드마크가 함께 언급됐다면 그 동네/랜드마크만 name으로 쓰고,
  상위 지역명은 region 필드에 넣으세요.
- 일반명사만 있고 고유명사가 없는 경우 (예: "카페", "시장" 단독)는 제외하세요.

일정 구조 판단:
이 영상이 여행 일정을 일자별(1일차, 2일차...)로 소개하는 구조인지 판단하세요.
자막/오디오/화면 텍스트에 "1일차", "Day 1", "첫째 날" 같은 명시적 표현이 있거나,
장소들이 명확하게 날짜 단위로 묶여 순서대로 소개되면 일정형입니다. 단순히 여러
장소를 나열만 하고 날짜 구분이 없으면 일정형이 아닙니다.

일정형이면 각 장소에 dayNumber(몇 일차인지)와 orderInDay(그 날 안에서 몇 번째로
소개됐는지)를 채우세요. 일정형이 아니면 두 필드 모두 null로 두세요.

각 항목은 다음 필드를 가집니다:
- name: 장소의 고유 이름 (가장 확신하는 표기)
- nameCandidates: name의 대안 철자 후보 배열. 자막/음성/화면 텍스트 인식이 헷갈릴 수 있는
  이름이면(예: 발음이 비슷한 다른 글자로도 들릴 수 있음) 대안 표기를 함께 담으세요.
  예: name이 "하늘기"인데 "하늘길"일 수도 있다면 ["하늘기", "하늘길"]. name 자체를
  포함해서 최대 3개까지. 확실해서 대안이 필요 없으면 [name]처럼 name 하나만 담으세요.
- region: 알 수 있는 상위 지역/도시명 (모르면 null)
- category: "restaurant" | "cafe" | "attraction" | "lodging" | "shopping" | "other" 중 하나
- confidence: 0~1 사이 숫자 (얼마나 확실한 장소명인지)
- dayNumber: 몇 일차인지 (1부터 시작하는 정수, 일정형이 아니면 null)
- orderInDay: 그 날 안에서의 순서 (1부터 시작하는 정수, 일정형이 아니면 null)

장소가 전혀 없으면 빈 배열 []을 반환하세요. JSON 배열 외의 다른 텍스트는 출력하지 마세요.
"""


def load_api_key() -> str:
    value = os.environ.get("GEMINI_API_KEY")
    if value:
        return value.strip()
    for path in (Path(__file__).resolve().parent / ".env", Path.cwd() / ".env"):
        if not path.exists():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, val = line.partition("=")
            if key.strip() != "GEMINI_API_KEY":
                continue
            val = val.strip().strip('"').strip("'")
            if val:
                return val
    raise SystemExit("GEMINI_API_KEY not set. pipeline-test/.env에 넣거나 export 하세요.")


def build_parts(
    transcript: str | None,
    audio_path: Path | None,
    frame_paths: list[Path] | None,
    prompt: str,
    candidates: list[str] | None = None,
) -> list[dict]:
    parts: list[dict] = []
    if transcript:
        parts.append({"text": f"자막/전사 텍스트:\n{transcript}"})
    if audio_path:
        mime = mimetypes.guess_type(audio_path.name)[0] or "audio/mp3"
        data = base64.b64encode(audio_path.read_bytes()).decode("ascii")
        parts.append({"inline_data": {"mime_type": mime, "data": data}})
        parts.append({"text": "위 오디오를 듣고 화자가 언급한 장소를 찾으세요."})
    if frame_paths:
        for frame_path in frame_paths:
            mime = mimetypes.guess_type(frame_path.name)[0] or "image/jpeg"
            data = base64.b64encode(frame_path.read_bytes()).decode("ascii")
            parts.append({"inline_data": {"mime_type": mime, "data": data}})
        parts.append({
            "text": "위 이미지들은 영상에서 시간순으로 뽑은 프레임입니다. "
            "화면에 적힌 자막/캡션/위치 태그 등 온스크린 텍스트에서 장소를 찾으세요."
        })
    if candidates is not None:
        parts.append({"text": f"1차 후보 목록: {json.dumps(candidates, ensure_ascii=False)}"})
    parts.append({"text": prompt})
    return parts


def _extract_text(payload: dict) -> str:
    try:
        return payload["candidates"][0]["content"]["parts"][0]["text"]
    except (KeyError, IndexError):
        raise SystemExit(f"Unexpected Gemini response shape: {json.dumps(payload)[:500]}")


def _normalize_day_fields(places: list[dict]) -> list[dict]:
    """Gemini가 dayNumber/orderInDay를 정수가 아닌 값(예: "1일차")으로 반환해도
    해당 필드만 null로 정규화한다 — 일정 판단 실패가 영상 전체 추출을 실패시키지 않도록.
    """
    for place in places:
        for key in ("dayNumber", "orderInDay"):
            value = place.get(key)
            if value is None:
                continue
            try:
                place[key] = int(value)
            except (TypeError, ValueError):
                place[key] = None
    return places


def _normalize_name_candidates(places: list[dict]) -> list[dict]:
    """nameCandidates가 없거나 배열이 아니거나 비어 있으면 [name] 하나로 대체한다 —
    지오코딩 재시도 후보 목록이 비어서 검색을 아예 못 도는 일이 없도록 한다.
    """
    for place in places:
        candidates = place.get("nameCandidates")
        if not isinstance(candidates, list) or not candidates:
            place["nameCandidates"] = [place.get("name")]
    return places


MAX_ATTEMPTS = 5
RETRY_BASE_DELAY = 5.0  # 무료 티어 RPM 제한 대응 — 429/일시 오류 시 지수 백오프

API_LOG_MARKER = "TROVA_API_LOG:"


def _log_api_call(
    operation: str, started_at: float, success: bool,
    payload: dict | None = None, error: str | None = None,
) -> None:
    """포트폴리오용 트래픽/비용 근거 기록 — 호출 1건당 지연시간·토큰·성공여부를
    stderr에 한 줄로 남긴다(Java Runner가 파싱해서 DB에 저장). stdout의 JSON
    계약은 절대 건드리지 않는다."""
    entry: dict = {
        "provider": "gemini",
        "operation": operation,
        "latencyMs": round((time.monotonic() - started_at) * 1000),
        "success": success,
    }
    if error is not None:
        entry["errorMessage"] = error[:500]
    usage = (payload or {}).get("usageMetadata") if payload else None
    if usage:
        entry["promptTokens"] = usage.get("promptTokenCount")
        entry["responseTokens"] = usage.get("candidatesTokenCount")
        entry["totalTokens"] = usage.get("totalTokenCount")
    print(f"{API_LOG_MARKER}{json.dumps(entry, ensure_ascii=False)}", file=sys.stderr)


def call_gemini(parts: list[dict], model: str, api_key: str, operation: str) -> dict:
    url = f"{API_BASE}/{model}:generateContent?key={api_key}"
    body = json.dumps({
        "contents": [{"role": "user", "parts": parts}],
        "generationConfig": {
            "temperature": 0.1,
            "responseMimeType": "application/json",
        },
    }).encode("utf-8")

    started_at = time.monotonic()
    last_detail = ""
    for attempt in range(MAX_ATTEMPTS):
        request = urllib.request.Request(
            url, data=body, headers={"Content-Type": "application/json"}, method="POST"
        )
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                payload = json.loads(response.read().decode("utf-8"))
                _log_api_call(operation, started_at, True, payload=payload)
                return payload
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            last_detail = detail

            # 429(요청 초과) 또는 5xx(서버 일시 오류)만 재시도. 그 외 4xx는 재시도해도 소용없음.
            if exc.code != 429 and not (500 <= exc.code < 600):
                _log_api_call(operation, started_at, False, error=f"Gemini API error {exc.code}: {detail[:500]}")
                raise SystemExit(f"Gemini API error {exc.code}: {detail[:500]}")

            if attempt < MAX_ATTEMPTS - 1:
                retry_after = exc.headers.get("Retry-After") if exc.headers else None
                delay = float(retry_after) if retry_after else RETRY_BASE_DELAY * (2 ** attempt)
                print(
                    f"[extract_places] Gemini {exc.code} — {delay:.0f}초 대기 후 재시도 "
                    f"({attempt + 2}/{MAX_ATTEMPTS})",
                    file=sys.stderr,
                )
                time.sleep(delay)
        except (urllib.error.URLError, TimeoutError) as exc:
            last_detail = str(exc)
            if attempt < MAX_ATTEMPTS - 1:
                delay = RETRY_BASE_DELAY * (attempt + 1)
                print(
                    f"[extract_places] 네트워크 오류({exc}) — {delay:.0f}초 대기 후 재시도 "
                    f"({attempt + 2}/{MAX_ATTEMPTS})",
                    file=sys.stderr,
                )
                time.sleep(delay)

    _log_api_call(operation, started_at, False, error=f"{MAX_ATTEMPTS}번 시도 후 실패: {last_detail[:500]}")
    raise SystemExit(f"Gemini API 요청이 {MAX_ATTEMPTS}번 시도 후 실패: {last_detail[:500]}")


def collect_candidates(
    transcript: str | None,
    audio_path: Path | None,
    frame_paths: list[Path] | None,
    model: str,
    api_key: str,
) -> list[str]:
    """1단계: 필터링 없이 장소일 가능성이 있는 고유명사를 최대한 후하게 나열한다."""
    parts = build_parts(transcript, audio_path, frame_paths, prompt=COLLECT_PROMPT)
    payload = call_gemini(parts, model, api_key, "extract_places.collect")
    text = _extract_text(payload)
    try:
        candidates = json.loads(text)
    except json.JSONDecodeError:
        raise SystemExit(f"Gemini did not return valid JSON (1단계): {text[:500]}")
    if not isinstance(candidates, list):
        raise SystemExit(f"Gemini 1단계 응답이 배열이 아닙니다: {text[:500]}")
    return candidates


def extract_places(
    transcript: str | None = None,
    audio_path: Path | None = None,
    frame_paths: list[Path] | None = None,
    model: str = DEFAULT_MODEL,
) -> list[dict]:
    if not transcript and not audio_path and not frame_paths:
        raise ValueError("transcript, audio_path, frame_paths 중 하나는 필요합니다")
    api_key = load_api_key()

    candidates = collect_candidates(transcript, audio_path, frame_paths, model, api_key)

    parts = build_parts(transcript, audio_path, frame_paths, prompt=FILTER_PROMPT, candidates=candidates)
    payload = call_gemini(parts, model, api_key, "extract_places.filter")
    text = _extract_text(payload)
    try:
        places = json.loads(text)
    except json.JSONDecodeError:
        raise SystemExit(f"Gemini did not return valid JSON (2단계): {text[:500]}")
    return _normalize_name_candidates(_normalize_day_fields(places))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(
            "usage: extract_places.py <transcript.txt | audio.mp3 | frames_dir>",
            file=sys.stderr,
        )
        raise SystemExit(2)

    input_path = Path(sys.argv[1])
    if not input_path.exists():
        raise SystemExit(f"file not found: {input_path}")

    if input_path.is_dir():
        frame_paths = sorted(input_path.glob("*.jpg")) + sorted(input_path.glob("*.jpeg"))
        if not frame_paths:
            raise SystemExit(f"no .jpg frames found in {input_path}")
        places = extract_places(frame_paths=frame_paths)
    elif input_path.suffix.lower() in (".txt", ".md"):
        places = extract_places(transcript=input_path.read_text(encoding="utf-8"))
    else:
        places = extract_places(audio_path=input_path)

    print(json.dumps(places, ensure_ascii=False, indent=2))
