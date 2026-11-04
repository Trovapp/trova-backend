#!/usr/bin/env python3
"""Python 제어 CLI(#312) — 기존 pipeline-test/extract_places.py 의 build_parts 를 그대로 불러 parts JSON 을 낸다.

이 프로그램은 Gemini 요청 본문을 만들 준비(parts 생성)까지만 한다. API 키를 읽지 않고(load_api_key 미호출),
네트워크·DB·대기열 코드를 타지 않는다. Rust 비교 프로그램(src/main.rs)과 같은 입력 계약을 쓴다.

입력 계약(매니페스트, JSON 객체 하나):
  prompt(문자열, 필수), transcript·audio_path·video_uri·post_description(문자열 또는 null),
  frame_paths·candidates(문자열 배열 또는 null), photo_post(불리언). 모르는 키는 오류.
출력: parts 배열을 UTF-8·공백 없는(compact)·객체 키 정렬 JSON 한 줄로 stdout 에 쓴다.
  배열 순서와 문자열은 build_parts 결과 그대로다. 실패하면 stdout 에 {"error":{"kind":...}} (지속 모드)
  또는 아무것도 쓰지 않고 종료 코드 1(한 번 실행 모드). 사람용 오류 설명은 stderr.

실행:
  python3 parts_control.py manifest.json         # 한 번 실행
  python3 parts_control.py --serve [--timing]     # 지속 프로세스: stdin 한 줄 = 요청 하나
"""
from __future__ import annotations

import json
import math
import re
import stat
import sys
import time
from pathlib import Path

PIPELINE_DIR = Path(__file__).resolve().parents[3] / "pipeline-test"
if str(PIPELINE_DIR) not in sys.path:
    sys.path.insert(0, str(PIPELINE_DIR))

import extract_places  # noqa: E402 — 기존 함수 재사용이 목적

MAX_INPUT_BYTES = 16 * 1024 * 1024  # 실험 자원 한도(설계 문서). Gemini 한도가 아니다.
MAX_JSON_DEPTH = 127  # serde_json 기본 재귀 한도(중첩 127단계까지 허용)와 맞춘다 — 실패 입력 시험으로 확인.

_SURROGATE = re.compile("[\ud800-\udfff]")

STR_FIELDS = ("transcript", "audio_path", "video_uri", "post_description")
LIST_FIELDS = ("frame_paths", "candidates")
ALL_FIELDS = frozenset(STR_FIELDS + LIST_FIELDS + ("prompt", "photo_post"))


class RequestError(Exception):
    def __init__(self, kind: str, detail: str = ""):
        super().__init__(f"{kind}: {detail}")
        self.kind = kind
        self.detail = detail


def _reject_constant(name: str):
    raise ValueError(f"non-standard JSON constant {name}")


def _parse_float(text: str) -> float:
    value = float(text)
    if not math.isfinite(value):
        raise ValueError("number out of range")
    return value


def _parse_int(text: str) -> int:
    if not math.isfinite(float(text)):
        raise ValueError("number out of range")
    return int(text)


def _check_strings_and_depth(value, depth: int = 0) -> None:
    """Rust(serde_json)이 파싱 단계에서 거절하는 값을 Python 쪽도 같은 오류 종류로 거절한다."""
    if isinstance(value, (list, dict)):
        depth += 1
        if depth > MAX_JSON_DEPTH:
            raise ValueError("recursion limit exceeded")
    if isinstance(value, str):
        if _SURROGATE.search(value):
            raise ValueError("lone surrogate")
    elif isinstance(value, list):
        for item in value:
            _check_strings_and_depth(item, depth)
    elif isinstance(value, dict):
        for key, item in value.items():
            _check_strings_and_depth(key, depth)
            _check_strings_and_depth(item, depth)


def parse_manifest(raw: bytes) -> dict:
    try:
        text = raw.decode("utf-8")
        data = json.loads(text, parse_constant=_reject_constant, parse_float=_parse_float, parse_int=_parse_int)
        _check_strings_and_depth(data)
    except (UnicodeDecodeError, ValueError, RecursionError) as exc:
        raise RequestError("invalid_json", str(exc)) from None
    if type(data) is not dict:
        raise RequestError("invalid_manifest", "top level must be an object")
    unknown = sorted(set(data) - ALL_FIELDS)
    if unknown:
        raise RequestError("invalid_manifest", f"unknown keys {unknown}")
    if type(data.get("prompt")) is not str:
        raise RequestError("invalid_manifest", "prompt must be a string")
    for key in STR_FIELDS:
        if key in data and data[key] is not None and type(data[key]) is not str:
            raise RequestError("invalid_manifest", f"{key} must be a string or null")
    for key in LIST_FIELDS:
        value = data.get(key)
        if value is None:
            continue
        if type(value) is not list or any(type(item) is not str for item in value):
            raise RequestError("invalid_manifest", f"{key} must be an array of strings or null")
    if "photo_post" in data and type(data["photo_post"]) is not bool:
        raise RequestError("invalid_manifest", "photo_post must be a boolean")
    return data


def _regular_size(path: Path) -> int:
    try:
        info = path.stat()
    except (OSError, ValueError):
        return 0  # 없는 파일 등은 여기서 판단하지 않는다 — build_parts 가 실제로 읽다가 실패한다.
    return info.st_size if stat.S_ISREG(info.st_mode) else 0


def build_request(data: dict) -> list[dict]:
    audio = data.get("audio_path")
    frames = data.get("frame_paths")
    audio_path = Path(audio) if audio is not None else None
    frame_paths = [Path(p) for p in frames] if frames is not None else None
    media = ([audio_path] if audio_path else []) + (frame_paths or [])
    total = sum(_regular_size(p) for p in media)
    if total > MAX_INPUT_BYTES:
        raise RequestError("input_too_large", f"{total} bytes > {MAX_INPUT_BYTES}")
    try:
        return extract_places.build_parts(
            data.get("transcript"),
            audio_path,
            frame_paths,
            prompt=data["prompt"],
            candidates=data.get("candidates"),
            video_uri=data.get("video_uri"),
            post_description=data.get("post_description"),
            photo_post=data.get("photo_post", False),
        )
    except FileNotFoundError as exc:
        raise RequestError("not_found", str(exc)) from None
    except IsADirectoryError as exc:
        raise RequestError("is_directory", str(exc)) from None
    except NotADirectoryError as exc:
        raise RequestError("not_a_directory", str(exc)) from None
    except PermissionError as exc:
        raise RequestError("permission_denied", str(exc)) from None
    except OSError as exc:
        raise RequestError("io_error", str(exc)) from None
    except ValueError as exc:  # 경로 안의 NUL 문자 등
        raise RequestError("invalid_path", str(exc)) from None


def encode_parts(parts: list[dict]) -> bytes:
    return json.dumps(parts, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8") + b"\n"


def encode_error(kind: str) -> bytes:
    return json.dumps({"error": {"kind": kind}}, separators=(",", ":"), sort_keys=True).encode("utf-8") + b"\n"


def handle(raw: bytes) -> tuple[bytes, str | None, str]:
    """요청 하나를 처리한다. (stdout 줄, 오류 종류 또는 None, 사람용 설명)."""
    try:
        return encode_parts(build_request(parse_manifest(raw))), None, ""
    except RequestError as exc:
        return encode_error(exc.kind), exc.kind, exc.detail


def _timing(kind: str | None, elapsed_ns: int, out_len: int) -> None:
    line = json.dumps({"impl": "python", "prep_ns": elapsed_ns, "out_bytes": out_len, "error": kind})
    sys.stderr.write("PARTS_TIMING " + line + "\n")
    sys.stderr.flush()


def serve(timing: bool) -> int:
    stdin = sys.stdin.buffer
    stdout = sys.stdout.buffer
    for line in stdin:
        if line.endswith(b"\n"):
            line = line[:-1]
        start = time.perf_counter_ns()
        out, kind, detail = handle(line)
        elapsed = time.perf_counter_ns() - start
        stdout.write(out)
        stdout.flush()
        if kind:
            sys.stderr.write(f"parts_control: {kind}: {detail}\n")
        if timing:
            _timing(kind, elapsed, len(out))
    return 0


def main(argv: list[str]) -> int:
    timing = "--timing" in argv
    args = [a for a in argv if a != "--timing"]
    if args == ["--serve"]:
        return serve(timing)
    if len(args) != 1 or args[0].startswith("--"):
        sys.stderr.write("usage: parts_control.py <manifest.json> | --serve [--timing]\n")
        return 2
    try:
        raw = Path(args[0]).read_bytes()
    except OSError as exc:
        sys.stderr.write(json.dumps({"error": {"kind": "manifest_unreadable", "detail": str(exc)}}) + "\n")
        return 1
    start = time.perf_counter_ns()
    out, kind, detail = handle(raw)
    elapsed = time.perf_counter_ns() - start
    if timing:
        _timing(kind, elapsed, len(out))
    if kind:
        # 실패하면 stdout 에 아무것도 쓰지 않는다(부분 출력·성공한 척 금지).
        sys.stderr.write(json.dumps({"error": {"kind": kind, "detail": detail}}, ensure_ascii=False) + "\n")
        return 1
    sys.stdout.buffer.write(out)
    sys.stdout.buffer.flush()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
