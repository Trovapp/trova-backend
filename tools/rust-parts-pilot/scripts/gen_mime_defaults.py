#!/usr/bin/env python3
"""Python mimetypes의 기본 표(시스템 파일을 읽기 전 값)를 Rust 소스로 옮긴다.

Rust 비교 프로그램은 이 표로 시작한 뒤, Python과 같은 순서로 시스템 mime.types 파일(knownfiles)을
실행할 때 읽는다. Python 버전이 바뀌면 기본 표가 달라질 수 있으므로 생성한 Python 버전을 파일에 남긴다.

실행: python3 tools/rust-parts-pilot/scripts/gen_mime_defaults.py [--check]
"""
from __future__ import annotations

import json
import mimetypes
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "src" / "mime_defaults.rs"


def _lit(value: str) -> str:
    # JSON 문자열 표기는 Rust 문자열 리터럴로도 유효하다(ASCII 표 기준).
    return json.dumps(value, ensure_ascii=True)


def render() -> str:
    lines = [
        "// 자동 생성 파일 — scripts/gen_mime_defaults.py 로 다시 만든다. 손으로 고치지 않는다.",
        f"// 생성 기준: Python {sys.version.split()[0]} mimetypes 기본 표",
        f"pub const PYTHON_VERSION: &str = {_lit(sys.version.split()[0])};",
        "",
        "pub const TYPES_MAP: &[(&str, &str)] = &[",
    ]
    for ext, mime in mimetypes._types_map_default.items():  # noqa: SLF001 — 기본 표 고정이 목적
        lines.append(f"    ({_lit(ext)}, {_lit(mime)}),")
    lines.append("];")
    lines.append("")
    lines.append("pub const ENCODINGS_MAP: &[(&str, &str)] = &[")
    for ext, enc in mimetypes._encodings_map_default.items():  # noqa: SLF001
        lines.append(f"    ({_lit(ext)}, {_lit(enc)}),")
    lines.append("];")
    lines.append("")
    lines.append("pub const SUFFIX_MAP: &[(&str, &str)] = &[")
    for ext, rep in mimetypes._suffix_map_default.items():  # noqa: SLF001
        lines.append(f"    ({_lit(ext)}, {_lit(rep)}),")
    lines.append("];")
    lines.append("")
    lines.append("pub const KNOWN_FILES: &[&str] = &[")
    for path in mimetypes.knownfiles:
        lines.append(f"    {_lit(path)},")
    lines.append("];")
    return "\n".join(lines) + "\n"


def main() -> int:
    text = render()
    if "--check" in sys.argv:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        # 버전 줄은 비교에서 뺀다 — 표 내용이 같은지만 본다.
        strip = lambda s: [l for l in s.splitlines() if "Python" not in l and "PYTHON_VERSION" not in l]
        if strip(current) != strip(text):
            print("mime_defaults.rs 가 현재 Python 기본 표와 다르다 — 다시 생성할 것", file=sys.stderr)
            return 1
        print("mime_defaults.rs 일치")
        return 0
    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
