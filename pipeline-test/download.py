#!/usr/bin/env python3
"""URL에서 영상 + (있으면) 자막을 함께 받는다.

YouTube가 2025년 하반기부터 PO Token 검증을 강화하면서 쿠키 없는 요청은
종종 403을 반환한다. 403이 뜨면 브라우저(Chrome) 로그인 쿠키로 자동 재시도한다.
"""
from __future__ import annotations

import re
import subprocess
import sys
import time
from pathlib import Path

SUB_LANGS = "ko,en"
MAX_ATTEMPTS = 4
RETRY_BASE_DELAY = 20.0  # 유튜브 자체 요청 속도 제한(429) 대응 — 넉넉하게 대기


def _run_yt_dlp(args: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(["yt-dlp", *args], capture_output=True, text=True)


def download(url: str, out_dir: Path) -> dict:
    """영상을 out_dir/video.* 로, 자막이 있으면 out_dir/video.*.vtt 로 받는다.

    반환: {"video_path": Path, "caption_path": Path | None}
    """
    # out_dir가 재사용될 경우(배치 재시도 등) 이전 영상의 파일이 남아있으면
    # 새 다운로드와 확장자가 달라 둘 다 존재하게 되고, 이후 video_candidates가
    # 크기순으로 골라서 엉뚱한(이전) 영상 파일을 집을 수 있음 — 매번 깨끗이 비운다.
    if out_dir.exists():
        for stale in out_dir.iterdir():
            stale.unlink()
    out_dir.mkdir(parents=True, exist_ok=True)
    out_tmpl = str(out_dir / "video.%(ext)s")
    base_args = [
        # 오디오는 16kHz 모노로 재인코딩하고 화면은 512px 폭 프레임 8장만
        # 뽑아 쓰므로(frames.py, run_pipeline.py), 원본을 최고화질로 받을
        # 이유가 없다 — 480p로 캡을 걸어 다운로드 시간만 줄인다.
        "-f", "bv*[height<=480]+ba/b[height<=480]",
        "--write-auto-sub", "--write-sub", "--sub-lang", SUB_LANGS,
        "--sub-format", "vtt",
        "-o", out_tmpl,
        url,
    ]

    result = None
    for attempt in range(MAX_ATTEMPTS):
        # 첫 시도 이후로는 항상 쿠키를 붙인다 — 로그인된 요청이 403(PO Token)과
        # 429(속도 제한) 둘 다에 대해 더 관대하게 처리되는 걸 실측으로 확인함.
        args = base_args if attempt == 0 else ["--cookies-from-browser", "chrome", *base_args]
        result = _run_yt_dlp(args)
        if result.returncode == 0:
            break

        rate_limited = "429" in result.stderr
        blocked = "403" in result.stderr
        if not (rate_limited or blocked) or attempt == MAX_ATTEMPTS - 1:
            break

        delay = RETRY_BASE_DELAY * (2 ** attempt)
        reason = "429 속도 제한" if rate_limited else "403 (PO Token 요구)"
        print(
            f"[download] {reason} 감지 — {delay:.0f}초 대기 후 재시도 "
            f"({attempt + 2}/{MAX_ATTEMPTS})",
            file=sys.stderr,
        )
        time.sleep(delay)

    if result.returncode != 0:
        raise SystemExit(f"yt-dlp 다운로드 실패:\n{result.stderr[-2000:]}")

    video_candidates = sorted(
        (p for p in out_dir.glob("video.*") if p.suffix not in (".vtt", ".json", ".part")),
        key=lambda p: p.stat().st_size,
        reverse=True,
    )
    if not video_candidates:
        raise SystemExit(f"yt-dlp가 영상 파일을 만들지 못했습니다 (out_dir={out_dir})")

    caption_candidates = sorted(out_dir.glob("video.*.vtt"))
    caption_path = caption_candidates[0] if caption_candidates else None

    return {"video_path": video_candidates[0], "caption_path": caption_path}


def get_title(url: str) -> str | None:
    """영상 제목을 가져온다. 실패해도 파이프라인 전체를 막지 않는다(best-effort) —
    다운로드/자막과 마찬가지로 별도 호출로 분리해서, 제목 조회 실패가 나머지 흐름을
    막지 않게 한다.
    """
    result = _run_yt_dlp(["--get-title", "--", url])
    if result.returncode != 0 and "403" in result.stderr:
        result = _run_yt_dlp(["--cookies-from-browser", "chrome", "--get-title", "--", url])
    if result.returncode != 0:
        print(f"[download] 제목 가져오기 실패(무시하고 진행): {result.stderr[-500:]}", file=sys.stderr)
        return None
    title = result.stdout.strip()
    return title or None


def vtt_to_text(vtt_path: Path) -> str:
    """WebVTT 자막에서 타임스탬프/태그를 제거하고 중복 없는 본문 텍스트만 뽑는다."""
    raw = vtt_path.read_text(encoding="utf-8", errors="replace")
    lines_out: list[str] = []
    last_line = None
    for line in raw.splitlines():
        line = line.strip()
        if not line or line == "WEBVTT":
            continue
        if "-->" in line:
            continue
        if re.match(r"^\d+$", line):
            continue
        # 태그(<c>, <00:00:01.000> 등) 제거
        clean = re.sub(r"<[^>]+>", "", line).strip()
        if not clean or clean == last_line:
            continue
        lines_out.append(clean)
        last_line = clean
    return "\n".join(lines_out)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: download.py <url> [out_dir]", file=sys.stderr)
        raise SystemExit(2)
    url = sys.argv[1]
    out_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("work/download")
    info = download(url, out_dir)
    print(f"video: {info['video_path']}")
    print(f"caption: {info['caption_path']}")
