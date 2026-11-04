#!/usr/bin/env python3
"""영상에서 균등 간격으로 프레임(JPEG)을 추출한다 (ffmpeg 필요)."""
from __future__ import annotations

import bisect
import json
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


def _clear(out_dir: Path) -> None:
    # out_dir가 재사용되고 이번 영상이 짧아서 이전 실행보다 프레임 수가 적게
    # 나오면, 이전 영상의 뒷번호 프레임(frame_005.jpg 등)이 안 지워지고
    # 남아 다음 Gemini 호출에 섞여 들어갈 수 있음 — 매번 깨끗이 비운다.
    if out_dir.exists():
        for stale in out_dir.glob("frame_*.jpg"):
            stale.unlink()
    out_dir.mkdir(parents=True, exist_ok=True)


def extract_frames(video_path: Path, out_dir: Path, max_frames: int = 8, width: int = 512) -> list[Path]:
    """영상을 max_frames 구간으로 나눠 각 구간 가운데 바로 앞 장면을 뽑는다.

    #323: 예전(extract_frames_full_decode)은 fps 필터로 영상 전체를 디코딩해 운영(1/8 OCPU)에서 인스타 1건에
    19.6초가 걸렸다. 같은 장면을 고르되 장면 시각 목록은 압축 데이터(패킷)만 읽어 구하고, 고른 지점으로 바로
    이동(-ss)해 그 장면만 디코딩한다. 시각을 읽지 못하면 예전 방식으로 뽑는다.
    """
    duration = probe_duration(video_path) or float(max_frames)
    interval = max(duration / max_frames, 0.5)
    if duration < SEEK_MIN_DURATION:
        # 짧은 영상은 전체 디코딩도 몇 초라 지점 이동 이득이 작고, 장면 시각을 읽는 비용(운영 약 0.5초)이 더 컸다.
        return extract_frames_full_decode(video_path, out_dir, max_frames, width, interval)
    times, keyframes = _frame_times(video_path)
    if not times:
        return extract_frames_full_decode(video_path, out_dir, max_frames, width, interval)
    picks = _pick_like_fps_filter(times, interval, max_frames)
    if not picks or not _seek_is_cheaper(times, keyframes, picks):
        # 키프레임이 드물면(운영 인스타 영상은 5초마다) 지점마다 앞 키프레임부터 디코딩해야 해서 짧은 영상은 오히려 느렸다.
        return extract_frames_full_decode(video_path, out_dir, max_frames, width, interval)
    _clear(out_dir)
    for cmd in _seek_commands(video_path, out_dir, picks, times[0], width):
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            print(f"[frames] 지점 이동 추출 실패 — 전체 디코딩으로 다시: {result.stderr[:200]}", file=sys.stderr)
            return extract_frames_full_decode(video_path, out_dir, max_frames, width, interval)
    return sorted(out_dir.glob("frame_*.jpg"))


def _seek_commands(video_path: Path, out_dir: Path, picks: list[float], start: float, width: int) -> list[list[str]]:
    """고른 지점마다 한 장씩 뽑는 ffmpeg 명령들. SEEK_INPUTS_PER_CALL개씩 한 명령에 묶는다.

    #327: 8곳을 한 명령에 열면 디코더 8개가 각자 스레드를 띄워 운영(2코어)에서 ffmpeg 메모리가 72.6→217.5MiB,
    CPU도 서로 빼앗았다. 입력마다 -threads 1, 2곳씩 나누니 9.5초·80MiB로 시간·메모리 모두 줄고 결과 JPEG는 같았다.
    """
    commands = []
    for base in range(0, len(picks), SEEK_INPUTS_PER_CALL):
        part = picks[base:base + SEEK_INPUTS_PER_CALL]
        cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y"]
        for t in part:
            # 입력 앞의 -ss는 가까운 키프레임부터 디코딩해 t 이전 장면은 버린다(정확한 이동). 반올림 오차로 한 장 밀리지 않게 조금 앞에서.
            cmd += ["-threads", "1", "-ss", f"{max(t - start - 0.0005, 0):.6f}", "-i", str(video_path)]
        for i in range(len(part)):
            cmd += ["-map", f"{i}:v:0", "-frames:v", "1", "-vf", f"scale={width}:-1",
                    str(out_dir / f"frame_{base + i + 1:03d}.jpg")]
        commands.append(cmd)
    return commands


def _frame_times(video_path: Path) -> tuple[list[float], list[float]]:
    """첫 영상 스트림의 (모든 장면 시각, 키프레임 시각)(초). 패킷 머리만 읽고 디코딩하지 않는다."""
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "packet=pts_time,flags",
         "-of", "csv=p=0", str(video_path)],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        return [], []
    times: list[float] = []
    keyframes: list[float] = []
    for line in result.stdout.split():
        fields = line.split(",")
        try:
            t = float(fields[0])
        except (ValueError, IndexError):
            continue
        times.append(t)
        if len(fields) > 1 and "K" in fields[1]:
            keyframes.append(t)
    return sorted(times), sorted(keyframes)


def _seek_is_cheaper(times: list[float], keyframes: list[float], picks: list[float]) -> bool:
    """디코딩할 장면 수로 비교한다. 지점 이동은 고른 장면마다 바로 앞 키프레임부터, 전체 디코딩은 마지막 고른 장면까지 다 읽는다."""
    if not keyframes:
        return False
    seek_cost = 0
    for pick in picks:
        key = max((k for k in keyframes if k <= pick), default=times[0])
        seek_cost += bisect.bisect_right(times, pick) - bisect.bisect_left(times, key)
    full_cost = bisect.bisect_right(times, picks[-1])
    return seek_cost < full_cost * SEEK_MAX_COST_RATIO


# 지점 이동은 입력을 고른 장면 수만큼 따로 열어 장면당 비용이 조금 더 든다. 운영 측정(docs/bench/frame-seek-20261104.md)에서
# 디코딩 장면 수 비율 약 0.4면 시간 약 0.55~0.6배, 1을 넘으면 더 느렸다. 이득이 확실한 0.7 미만일 때만 쓴다.
SEEK_MAX_COST_RATIO = 0.7
# 운영 19초 vp9 영상: 전체 디코딩 3.0초, 시각 읽기를 더하면 3.5초. 이보다 짧으면 시각을 읽지 않는다.
SEEK_MIN_DURATION = 20.0
# 한 ffmpeg 명령에 여는 입력 수. 운영 46초 영상: 8개 164.8MiB·9.0초, 4개 106.7MiB·9.2초, 2개 80.2MiB·9.5초(docs/bench/insta-e2e-after-frame-seek-20261104.md).
SEEK_INPUTS_PER_CALL = 2


def _pick_like_fps_filter(times: list[float], interval: float, max_frames: int) -> list[float]:
    """fps=1/interval 필터가 고르는 장면과 같게: k번째는 (k+0.5)×interval 직전의 마지막 장면.

    운영 영상 3개에서 예전 방식이 뽑은 8장을 전체 장면과 대조해 확인한 규칙이다(docs/bench/frame-seek-20261104.md).
    """
    start = times[0]
    # 영상 끝 = 마지막 장면 + 장면 하나 길이. 짧은 영상(구간 0.5초 하한)에서 끝을 넘는 구간은 예전처럼 뽑지 않는다.
    gaps = sorted(b - a for a, b in zip(times, times[1:]) if b > a)
    end = times[-1] + (gaps[len(gaps) // 2] if gaps else 0.0)
    picks: list[float] = []
    j = 0
    for k in range(max_frames):
        target = start + (k + 0.5) * interval
        if target >= end:
            break
        while j + 1 < len(times) and times[j + 1] < target:
            j += 1
        picks.append(times[j])
    return picks


def extract_frames_full_decode(
    video_path: Path, out_dir: Path, max_frames: int = 8, width: int = 512, interval: float | None = None
) -> list[Path]:
    """예전 방식(#323 전): fps 필터로 영상 전체를 디코딩한다. 지점 이동이 이득이 없을 때와 비교 측정에 쓴다.
    interval을 받으면 영상 길이를 다시 읽지 않는다."""
    _clear(out_dir)
    if interval is None:
        duration = probe_duration(video_path) or float(max_frames)
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


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: frames.py <video_path> [out_dir] [max_frames]", file=sys.stderr)
        raise SystemExit(2)
    video_path = Path(sys.argv[1])
    out_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("work/frames")
    max_frames = int(sys.argv[3]) if len(sys.argv) > 3 else 8
    for frame in extract_frames(video_path, out_dir, max_frames=max_frames):
        print(frame)
