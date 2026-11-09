#!/usr/bin/env python3
"""URL에서 영상 + (있으면) 자막을 함께 받는다.

YouTube가 2025년 하반기부터 PO Token 검증을 강화하면서 쿠키 없는 요청은
종종 403을 반환한다. 403이 뜨면 브라우저(Chrome) 로그인 쿠키로 자동 재시도한다.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import time
from pathlib import Path

SUB_LANGS = "ko,en"
MAX_ATTEMPTS = 4
RETRY_BASE_DELAY = 20.0  # 유튜브 자체 요청 속도 제한(429) 대응 — 넉넉하게 대기
# 403(PO Token)·429 재시도에 브라우저 로그인 쿠키를 쓸지(예: "chrome"). 운영 서버에는 브라우저가 없어 쿠키 시도가 항상
# 실패하는데도 그 전에 20초를 쉬고 있었다(#257 D). 비우면(기본) 쿠키 없이 돌고, 403은 기다리지 않고 바로 실패한다.
# 켜면 다른 사용자가 보낸 주소도 이 맥의 개인 로그인 세션으로 조회하게 되므로 개발자 혼자 쓰는 로컬에서만 켠다(공용 서버 금지).
BROWSER_COOKIES = os.environ.get("PIPELINE_BROWSER_COOKIES", "").strip()
# 프레임은 가로 512px로 줄여 쓰므로(frames.py) 짧은 변 512 이상 중 가장 작은 화질이면 충분하다(#7, #350).
# 기본값은 쇼츠를 4K(84MB)로 받아 다운로드·프레임 추출이 느렸다. 정렬 조건이라 맞는 형식이 없어도 실패하지 않는다
# (높이 필터 "height<=480"은 세로 360x640만 주는 릴스에서 형식 없음으로 실패했다).
# #350: res:720 → res~620(620에 가장 가까운 것). 릴스는 360·540·720·1080 중 일부를 준다.
# - 540×960이 있으면 그것을 받는다: 디코딩 CPU가 720×1280보다 41% 적었다(로컬, 같은 vp9 릴스). 프레임 크기 512는 그대로.
# - 540이 없으면 360이 아니라 720을 받는다(360은 프레임을 키워야 해 글자가 흐려진다).
#   "res:540"(540 이하 중 최대)은 360×640을 받거나, 크기 정보 없는 형식을 고르는 릴스가 있어 쓰지 않았다.
MAX_RESOLUTION_SORT = "res~620"


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
        "-f", "bv*+ba/b", "-S", MAX_RESOLUTION_SORT,
        "--write-auto-sub", "--write-sub", "--sub-lang", SUB_LANGS,
        "--sub-format", "vtt",
        "-o", out_tmpl,
        url,
    ]

    result = None
    for attempt in range(MAX_ATTEMPTS):
        # 첫 시도 이후로는 항상 쿠키를 붙인다 — 로그인된 요청이 403(PO Token)과
        # 429(속도 제한) 둘 다에 대해 더 관대하게 처리되는 걸 실측으로 확인함.
        args = base_args if attempt == 0 or not BROWSER_COOKIES else ["--cookies-from-browser", BROWSER_COOKIES, *base_args]
        result = _run_yt_dlp(args)
        if result.returncode == 0:
            break

        rate_limited = "429" in result.stderr
        blocked = "403" in result.stderr
        if not (rate_limited or blocked) or attempt == MAX_ATTEMPTS - 1:
            break
        if blocked and not rate_limited and not BROWSER_COOKIES:
            print("[download] 403 (PO Token 요구) — 브라우저 쿠키가 없어 재시도하지 않음", file=sys.stderr)
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
    if result.returncode != 0 and "403" in result.stderr and BROWSER_COOKIES:
        result = _run_yt_dlp(["--cookies-from-browser", BROWSER_COOKIES, "--get-title", "--", url])
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
