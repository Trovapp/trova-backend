#!/usr/bin/env python3
"""영상에서 균등 간격으로 프레임(JPEG)을 추출한다 (ffmpeg 필요)."""
from __future__ import annotations

import json
import math
import subprocess
import sys
from pathlib import Path


def probe_duration(video_path: Path) -> float:
    result = subprocess.run(
        ["ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", str(video_path)],
        capture_output=True,
        text=True,
    )
    fmt = json.loads(result.stdout or "{}").get("format", {})
    return float(fmt.get("duration") or 0.0)


MIN_FRAMES = 8
MAX_FRAMES_CAP = 20
TARGET_INTERVAL_SEC = 3.0  # 이 간격보다 촘촘하게 뽑아야 화면 캡션/위치태그를 안 놓침
# (52초 쇼츠에 장소 6곳이 몰려 있던 실측 샘플에서 고정 8장(6.6초 간격)일 땐
# 2곳을 놓치거나 이름을 잘못 인식했는데, 16장(3.3초 간격)으로 늘리니 5/6으로
# 개선됨 — 영상 길이와 무관한 고정 프레임 수가 원인이었음을 확인하고 이 값으로
# 결정함)


def extract_frames(video_path: Path, out_dir: Path, max_frames: int | None = None, width: int = 512) -> list[Path]:
    # out_dir가 재사용되고 이번 영상이 짧아서 이전 실행보다 프레임 수가 적게
    # 나오면, 이전 영상의 뒷번호 프레임(frame_005.jpg 등)이 안 지워지고
    # 남아 다음 Gemini 호출에 섞여 들어갈 수 있음 — 매번 깨끗이 비운다.
    if out_dir.exists():
        for stale in out_dir.glob("frame_*.jpg"):
            stale.unlink()
    out_dir.mkdir(parents=True, exist_ok=True)
    duration = probe_duration(video_path) or float(MIN_FRAMES)

    if max_frames is None:
        # 영상 길이에 비례해 프레임 수를 정한다 — 짧은 영상도 최소 밀도를
        # 보장하고(MIN_FRAMES), 긴 영상은 토큰/속도 폭주를 막게 상한을 둔다.
        max_frames = min(MAX_FRAMES_CAP, max(MIN_FRAMES, math.ceil(duration / TARGET_INTERVAL_SEC)))

    interval = max(duration / max_frames, 0.5)

    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(video_path),
        "-vf", f"fps=1/{interval:.3f},scale={width}:-1",
        "-vframes", str(max_frames),
        str(out_dir / "frame_%03d.jpg"),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"ffmpeg 프레임 추출 실패: {result.stderr}")
    return sorted(out_dir.glob("frame_*.jpg"))


N_COLLECT_PASSES = 3
# 프레임을 한 번에 많이(예: 35장) 밀어넣으면 모델이 비슷한 후보끼리 헷갈려서
# 바꿔치기하는 오류가 생김(실측: "부전시장"이 "부전역"으로 잘못 대체됨).
# 대신 같은 밀도를 3번의 작은 배치(각 16장, 서로 다른 시점 오프셋)로 나눠
# 수집하고 최종 판단(filter)만 한 번에 모아서 하면 recall과 precision을
# 동시에 얻을 수 있음을 실측으로 확인함 — 부산 쇼츠 샘플에서 6/6 100%
# (기존 단일 35프레임 호출은 6/6이지만 오류 1건 포함).


def extract_frame_sets(
    video_path: Path, out_dir: Path, n_passes: int = N_COLLECT_PASSES,
    max_frames: int | None = None, width: int = 512,
) -> list[list[Path]]:
    """같은 영상에서 시점을 서로 어긋나게(offset) n_passes번 프레임을 뽑는다.

    각 패스는 독립적으로 collect_candidates()에 넣을 용도 — 패스 사이 간격을
    좁혀서(원래 간격을 n_passes로 쪼갬) 합쳤을 때 훨씬 촘촘한 시간 해상도를
    얻으면서도, 개별 필터 호출에는 한 번에 16장씩만 들어가지 않게 한다
    (한 번의 collect 호출 자체는 여전히 가볍게 유지).
    """
    duration = probe_duration(video_path) or float(MIN_FRAMES)
    if max_frames is None:
        max_frames = min(MAX_FRAMES_CAP, max(MIN_FRAMES, math.ceil(duration / TARGET_INTERVAL_SEC)))
    interval = max(duration / max_frames, 0.5)
    offset_step = interval / n_passes

    frame_sets: list[list[Path]] = []
    for i in range(n_passes):
        pass_dir = out_dir / f"pass_{i}"
        if pass_dir.exists():
            for stale in pass_dir.glob("frame_*.jpg"):
                stale.unlink()
        pass_dir.mkdir(parents=True, exist_ok=True)

        offset = offset_step * i
        cmd = [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-ss", f"{offset:.3f}",
            "-i", str(video_path),
            "-vf", f"fps=1/{interval:.3f},scale={width}:-1",
            "-vframes", str(max_frames),
            str(pass_dir / "frame_%03d.jpg"),
        ]
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            raise SystemExit(f"ffmpeg 프레임 추출 실패(pass {i}): {result.stderr}")
        frame_sets.append(sorted(pass_dir.glob("frame_*.jpg")))
    return frame_sets


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: frames.py <video_path> [out_dir] [max_frames]", file=sys.stderr)
        raise SystemExit(2)
    video_path = Path(sys.argv[1])
    out_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("work/frames")
    max_frames = int(sys.argv[3]) if len(sys.argv) > 3 else None
    for frame in extract_frames(video_path, out_dir, max_frames=max_frames):
        print(frame)
