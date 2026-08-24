#!/usr/bin/env python3
"""URL 하나를 받아 끝까지 돌리는 파이프라인.

어떤 영상이 들어올지 모르므로, 구할 수 있는 신호(자막/오디오/화면 프레임)를
전부 모아 한 번에 Gemini로 보낸다. 무료 티어는 토큰 자체를 과금하지 않으므로
자막이 있다고 오디오를 생략할 이유가 없다 — 오히려 유튜브 자체 자막(ASR)도
오인식이 있을 수 있어서, 자막/오디오/화면 텍스트가 서로 교차검증돼야 장소명
정확도가 더 높아진다(예: 자막엔 없지만 화면 캡션에만 적힌 장소, 자막 오인식을
오디오로 바로잡는 경우 등).
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import download
import frames as frames_mod
from extract_places import extract_places

MAX_FRAMES = 8


def extract_audio(video_path: Path, out_path: Path) -> Path:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(video_path),
        "-vn", "-acodec", "libmp3lame", "-ar", "16000", "-ac", "1", "-b:a", "64k",
        str(out_path),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"ffmpeg 오디오 추출 실패: {result.stderr}")
    return out_path


def run(url: str, work_dir: Path) -> dict:
    print(f"[pipeline] 다운로드 중: {url}", file=sys.stderr)
    info = download.download(url, work_dir / "download")
    video_path = info["video_path"]
    caption_path = info["caption_path"]

    title = download.get_title(url)
    print(f"[pipeline] 제목: {title!r}", file=sys.stderr)

    transcript = None
    if caption_path:
        transcript = download.vtt_to_text(caption_path)
        print(f"[pipeline] 자막 발견 ({len(transcript)}자)", file=sys.stderr)
    else:
        print("[pipeline] 자막 없음", file=sys.stderr)

    print("[pipeline] 오디오 추출", file=sys.stderr)
    audio_path = extract_audio(video_path, work_dir / "audio.mp3")

    print(f"[pipeline] 프레임 추출 (최대 {MAX_FRAMES}장)", file=sys.stderr)
    frame_paths = frames_mod.extract_frames(video_path, work_dir / "frames", max_frames=MAX_FRAMES)

    print("[pipeline] Gemini 호출", file=sys.stderr)
    places = extract_places(transcript=transcript, audio_path=audio_path, frame_paths=frame_paths)
    return {"title": title, "places": places}


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: run_pipeline.py <url> [work_dir]", file=sys.stderr)
        raise SystemExit(2)
    url = sys.argv[1]
    work_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("work") / "run"
    result = run(url, work_dir)

    import json
    print(json.dumps(result, ensure_ascii=False, indent=2))
