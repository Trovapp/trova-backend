#!/usr/bin/env python3
"""통합 경로 비용 1회성 측정(#312) — 로컬 macOS 값, 운영 수치 아님.

실제 파이프라인은 Python 프로세스가 parts(파이썬 리스트)를 만든 뒤 Gemini 로 보낸다. Rust 를 쓰려면
Python 이 Rust 보조 프로세스에 요청을 보내고 결과 JSON 을 다시 파이썬 리스트로 읽어야 한다.
같은 끝점(메모리에 parts 리스트가 준비된 시점)까지의 시간을 세 방식으로 잰다.

  direct            기존 extract_places.build_parts 를 같은 프로세스에서 호출 (현재 방식)
  rust_spawn        요청마다 Rust 보조 프로세스를 새로 띄움 → 출력 json.loads
  rust_persistent   Rust 보조 프로세스 하나를 계속 띄워 두고 줄 단위로 요청 → 출력 json.loads

방식마다 별도의 Python 프로세스(--variant)로 실행하고, 그 프로세스와(보조 프로세스가 있으면) 자식의 최대 RSS 를 따로 기록한다.
부모·자식 최대 RSS 를 더해 동시 최대라고 하지 않는다. 결과 리스트가 direct 와 같은지 매번 확인한다.

실행: python3 tools/rust-parts-pilot/scripts/integration_cost.py [--reps 3] [--out ...json]
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import resource
import statistics
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import GEN, REPO, RESULTS, environment, load_lock, rust_cmd  # noqa: E402

VARIANTS = ("direct", "rust_spawn", "rust_persistent")


def variant_main(variant: str, reps: int, warmup: int) -> None:
    sys.path.insert(0, str(REPO / "pipeline-test"))
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "python"))
    import parts_control  # noqa: PLC0415 — 같은 계약 검사 + build_parts 호출

    import extract_places  # noqa: PLC0415

    def direct_build(req: dict) -> list:
        audio = req.get("audio_path")
        frames = req.get("frame_paths")
        return extract_places.build_parts(
            req.get("transcript"), Path(audio) if audio is not None else None,
            [Path(p) for p in frames] if frames is not None else None, prompt=req["prompt"],
            candidates=req.get("candidates"), video_uri=req.get("video_uri"),
            post_description=req.get("post_description"), photo_post=req.get("photo_post", False))

    os.chdir(GEN)
    lock = load_lock()
    helper = None
    if variant == "rust_persistent":
        helper = subprocess.Popen(rust_cmd() + ["--serve"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    samples = []
    mismatches = 0
    try:
        for fx in lock["fixtures"]:
            lines = (GEN / fx["requestFile"]).read_bytes().splitlines()
            # 요청 해석·계약 검사는 시간 밖에서 한 번만 한다 — 실제 파이프라인은 이미 파이썬 값을 들고 있다.
            requests = [parts_control.parse_manifest(l) for l in lines]
            reference = [direct_build(r) for r in requests]
            for rep in range(warmup + reps):
                t0 = time.perf_counter_ns()
                got = []
                for req in requests:
                    if variant == "direct":
                        got.append(direct_build(req))
                        continue
                    # Rust 로 보내려면 파이썬 값을 요청 JSON 으로 바꿔야 한다(이 비용도 포함).
                    line = json.dumps(req, ensure_ascii=False).encode("utf-8")
                    if variant == "rust_spawn":
                        r = subprocess.run(rust_cmd() + ["--serve"], input=line + b"\n", capture_output=True, check=True)
                        got.append(json.loads(r.stdout))
                    else:
                        helper.stdin.write(line + b"\n")
                        helper.stdin.flush()
                        got.append(json.loads(helper.stdout.readline()))
                t1 = time.perf_counter_ns()
                if got != reference:
                    mismatches += 1
                samples.append({"fixture": fx["id"], "rep": rep, "warmup": rep < warmup, "wallNs": t1 - t0})
                del got
            del reference
    finally:
        if helper:
            helper.stdin.close()
            helper.wait()
    print(json.dumps({
        "variant": variant, "samples": samples, "mismatches": mismatches,
        "selfMaxRssBytes": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
        "childrenMaxRssBytes": resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss,
        "childrenNote": "수거한 자식 중 가장 큰 최대 RSS(자식 합계 아님)",
    }))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reps", type=int, default=3)
    parser.add_argument("--warmup", type=int, default=1)
    parser.add_argument("--variant", choices=VARIANTS)
    parser.add_argument("--out", default=str(RESULTS / f"rust-parts-pilot-integration-{dt.date.today():%Y%m%d}.json"))
    args = parser.parse_args()
    if args.variant:
        variant_main(args.variant, args.reps, args.warmup)
        return 0

    runs = {}
    for variant in VARIANTS:
        t0 = time.perf_counter_ns()
        r = subprocess.run([sys.executable, __file__, "--variant", variant, "--reps", str(args.reps), "--warmup", str(args.warmup)],
                           capture_output=True, check=True)
        data = json.loads(r.stdout)
        data["processWallNs"] = time.perf_counter_ns() - t0
        runs[variant] = data

    summary = {}
    for variant, data in runs.items():
        measured = [s for s in data["samples"] if not s["warmup"]]
        per_fx = {}
        for s in measured:
            per_fx.setdefault(s["fixture"], []).append(s["wallNs"] / 1e6)
        summary[variant] = {
            "observations": len(measured),
            "wallMsMedian": statistics.median(s["wallNs"] / 1e6 for s in measured),
            "wallMsMean": statistics.fmean(s["wallNs"] / 1e6 for s in measured),
            "perFixtureMedianMs": {k: statistics.median(v) for k, v in per_fx.items()},
            "selfMaxRssBytes": data["selfMaxRssBytes"],
            "childrenMaxRssBytes": data["childrenMaxRssBytes"],
            "mismatches": data["mismatches"],
        }
    report = {
        "label": "로컬 macOS 1회성 통합 경로 측정 — 운영(Oracle E2.1.Micro, Linux) 수치가 아님",
        "issue": "#312",
        "environment": environment(),
        "config": {"reps": args.reps, "warmup": args.warmup, "order": list(VARIANTS),
                   "observationDefinition": "같은 입력의 collect+filter parts 를 Python 리스트로 얻을 때까지(요청 2개)",
                   "limits": "방식별 1회 실행(순서 고정 direct→spawn→persistent), 교대·반복 라운드 없음. 프로세스 트리 주기 샘플 없음."},
        "summary": summary,
        "raw": runs,
    }
    Path(args.out).write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    for v, s in summary.items():
        print(f"{v:16s} 중앙값 {s['wallMsMedian']:8.3f}ms 평균 {s['wallMsMean']:8.3f}ms  Python 최대RSS {s['selfMaxRssBytes'] / 1048576:6.1f}MiB  "
              f"자식 최대RSS {s['childrenMaxRssBytes'] / 1048576:6.1f}MiB  불일치 {s['mismatches']}")
    return 0 if all(s["mismatches"] == 0 for s in summary.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
