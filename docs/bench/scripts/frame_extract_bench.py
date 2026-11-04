#!/usr/bin/env python3
"""#323 프레임 뽑기 전후 시간 비교. 같은 영상에서 예전(전체 디코딩)·새(지점 이동)를 번갈아 순서를 바꿔 재고,
걸린 시간과 ffmpeg·ffprobe CPU 시간(자식 프로세스)을 모두 남긴다. Gemini·DB·네트워크를 쓰지 않는다.

python3 frame_extract_bench.py --frames-dir pipeline-test --label local --reps 3 --output out.json video1.mp4 ...
"""
import argparse
import json
import platform
import resource
import statistics
import sys
import tempfile
import time
from pathlib import Path


def child_cpu() -> float:
    usage = resource.getrusage(resource.RUSAGE_CHILDREN)
    return usage.ru_utime + usage.ru_stime


def timed(fn, video: Path, out: Path) -> dict:
    cpu0, wall0 = child_cpu(), time.perf_counter()
    frames = fn(video, out)
    return {'wallMs': round((time.perf_counter() - wall0) * 1000, 1), 'childCpuMs': round((child_cpu() - cpu0) * 1000, 1),
            'frames': len(frames)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--frames-dir', required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--reps', type=int, default=3)
    parser.add_argument('--output', required=True)
    parser.add_argument('videos', nargs='+')
    args = parser.parse_args()
    sys.path.insert(0, args.frames_dir)
    import frames
    methods = {'fullDecode': frames.extract_frames_full_decode, 'seek': frames.extract_frames}
    result = {'issue': 323, 'label': args.label, 'platform': platform.platform(), 'python': platform.python_version(),
              'method': {'warmupPerMethod': 1, 'measuredPerMethod': args.reps,
                         'order': '짝수 회차 fullDecode→seek, 홀수 회차 seek→fullDecode, 동시성 1'},
              'videos': []}
    with tempfile.TemporaryDirectory() as tmp:
        for video in map(Path, args.videos):
            row = {'video': video.parent.name + '/' + video.name, 'bytes': video.stat().st_size, 'samples': {m: [] for m in methods}}
            for name, fn in methods.items():
                row['warmup_' + name] = timed(fn, video, Path(tmp) / name)
            for rep in range(args.reps):
                order = list(methods) if rep % 2 == 0 else list(reversed(methods))
                for name in order:
                    row['samples'][name].append(timed(methods[name], video, Path(tmp) / name))
            for name in methods:
                walls = [s['wallMs'] for s in row['samples'][name]]
                cpus = [s['childCpuMs'] for s in row['samples'][name]]
                row[name + 'MedianWallMs'] = statistics.median(walls)
                row[name + 'MedianChildCpuMs'] = statistics.median(cpus)
            print(row['video'], 'fullDecode', row['fullDecodeMedianWallMs'], 'seek', row['seekMedianWallMs'], flush=True)
            result['videos'].append(row)
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
