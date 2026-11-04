#!/usr/bin/env python3
"""로컬 시간·메모리 측정(#312) — Python 제어 CLI 와 Rust CLI 의 parts 준비 비교.

**이 스크립트의 결과는 실행한 컴퓨터(로컬 macOS)의 값이며 운영(Oracle E2.1.Micro, Linux) 수치가 아니다.**

측정 규칙(설계 docs/superpowers/specs/2026-11-04-rust-pipeline-pilot.md)
- 관측 1개 = 같은 고정 입력의 collect 준비 + filter 준비 2회(줄 2개). 두 요청 각각의 내부 준비 시간도 남긴다.
- 묶음(lane) 두 개:
  fresh      관측마다 새 프로세스를 띄운다. 벽시계 시간 = 프로세스 생성 직전 → 종료 수거(wait4) 직후.
             최대 RSS·CPU 시간은 그 프로세스 하나의 getrusage 값(/usr/bin/time -l 이 보여주는 값과 같은 출처).
  persistent 입력마다 구현별 프로세스를 하나 띄워 워밍업 1회 + 측정 N회를 같은 PID 에서 처리한다.
             벽시계 시간 = 첫 요청 쓰기 직전 → 두 번째 응답 읽기 직후. 최대 RSS 는 그 프로세스 묶음 전체 값이다
             (개별 요청의 최대가 아니다).
- 입력 순서는 random.Random(seed).shuffle 로 고정하고, (입력 위치 + 반복 번호)의 홀짝으로 먼저 실행할 구현을 교대한다.
- 동시성 1(한 번에 요청 하나). 워밍업 관측은 따로 보존한다. 파일 캐시를 비우지 않는다.
- 매 관측의 stdout 해시를 기대 출력(Python 첫 실행)과 비교해 다른 출력이 나오면 mismatch 로 표시한다.

실행:
  python3 tools/rust-parts-pilot/scripts/bench.py [--reps 3] [--warmup 1] [--seed 309] [--aa] [--out ...json]
  --aa: Rust 대신 Python 을 한 번 더(A/A) 넣어 측정기 흔들림을 본다.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import random
import re
import statistics
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import GEN, RESULTS, commands, environment, load_lock, sha256_bytes  # noqa: E402

TIMING_RE = re.compile(rb"^PARTS_TIMING (.*)$", re.M)


def _parse_timings(stderr: bytes) -> list[dict]:
    return [json.loads(m.group(1)) for m in TIMING_RE.finditer(stderr)]


def _rusage(ru) -> dict:
    return {"maxRssBytes": ru.ru_maxrss, "userCpuSec": ru.ru_utime, "sysCpuSec": ru.ru_stime}


class Child:
    """wait4 로 수거해 그 프로세스만의 rusage 를 얻는다."""

    def __init__(self, cmd: list[str]):
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=GEN)
        self._err: list[bytes] = []
        self._err_thread = threading.Thread(target=lambda: self._err.append(self.proc.stderr.read()), daemon=True)
        self._err_thread.start()

    def finish(self) -> tuple[int, dict, bytes]:
        _, status, ru = os.wait4(self.proc.pid, 0)
        self.proc.returncode = os.waitstatus_to_exitcode(status)
        self._err_thread.join()
        return self.proc.returncode, _rusage(ru), b"".join(self._err)

    def kill(self) -> None:
        if self.proc.returncode is None:
            self.proc.kill()
            try:
                self.finish()
            except ChildProcessError:
                pass


def fresh_observation(cmd: list[str], lines: list[bytes]) -> dict:
    payload = b"\n".join(lines) + b"\n"
    t0 = time.perf_counter_ns()
    child = Child(cmd + ["--serve", "--timing"])
    try:
        def feed():
            child.proc.stdin.write(payload)
            child.proc.stdin.close()

        feeder = threading.Thread(target=feed, daemon=True)
        feeder.start()
        out = child.proc.stdout.read()
        feeder.join()
        code, ru, err = child.finish()
    except BaseException:
        child.kill()
        raise
    t1 = time.perf_counter_ns()
    timings = _parse_timings(err)
    return {"wallNs": t1 - t0, "exit": code, "pid": child.proc.pid, **ru,
            "prepNs": [t["prep_ns"] for t in timings], "outSha256": sha256_bytes(out), "outBytes": len(out)}


def persistent_observation(child: Child, lines: list[bytes]) -> dict:
    t0 = time.perf_counter_ns()
    out = b""
    for line in lines:  # collect 응답을 받은 뒤 filter 를 보낸다(실제 파이프라인 순서, 파이프 교착 방지)
        child.proc.stdin.write(line + b"\n")
        child.proc.stdin.flush()
        out += child.proc.stdout.readline()
    t1 = time.perf_counter_ns()
    return {"wallNs": t1 - t0, "pid": child.proc.pid, "outSha256": sha256_bytes(out), "outBytes": len(out)}


def summarize(values: list[float]) -> dict | None:
    if not values:
        return None
    return {"n": len(values), "mean": statistics.fmean(values), "median": statistics.median(values),
            "min": min(values), "max": max(values),
            "stdev": statistics.stdev(values) if len(values) > 1 else None}


def run(args) -> dict:
    lock = load_lock()
    all_cmds = commands()
    if args.aa:
        impls = {"python_a": all_cmds["python"], "python_b": all_cmds["python"]}
    else:
        impls = {"python": all_cmds["python"], "rust": all_cmds["rust"]}
    names = list(impls)
    fixtures = {fx["id"]: (GEN / fx["requestFile"]).read_bytes().splitlines() for fx in lock["fixtures"]}
    order = sorted(fixtures)
    random.Random(args.seed).shuffle(order)
    expected: dict[str, str] = {}
    observations: list[dict] = []
    bundles: list[dict] = []
    started = dt.datetime.now().astimezone().isoformat(timespec="seconds")

    for lane in args.lanes:
        for pos, fid in enumerate(order):
            lines = fixtures[fid]
            children: dict[str, Child] = {}
            try:
                if lane == "persistent":
                    for name in names:
                        children[name] = Child(impls[name] + ["--serve", "--timing"])
                for rep in range(args.warmup + args.reps):
                    first = names[(pos + rep) % 2]
                    seq = [first] + [n for n in names if n != first]
                    for slot, name in enumerate(seq):
                        if lane == "fresh":
                            obs = fresh_observation(impls[name], lines)
                        else:
                            obs = persistent_observation(children[name], lines)
                        if name == names[0]:
                            expected.setdefault(fid, obs["outSha256"])
                        obs.update({"lane": lane, "fixture": fid, "fixturePosition": pos, "rep": rep,
                                    "warmup": rep < args.warmup, "impl": name, "slotInPair": slot})
                        observations.append(obs)
                if lane == "persistent":
                    for name, child in children.items():
                        child.proc.stdin.close()
                        code, ru, err = child.finish()
                        timings = _parse_timings(err)
                        bundles.append({"lane": lane, "fixture": fid, "impl": name, "pid": child.proc.pid, "exit": code, **ru,
                                        "requestsServed": len(timings), "prepNs": [t["prep_ns"] for t in timings]})
            finally:
                for child in children.values():
                    child.kill()  # 중간에 멈추거나 예외가 나도 자식 프로세스를 남기지 않는다

    # persistent 의 요청별 준비 시간을 관측에 붙인다(요청 순서 = 관측 순서).
    for b in bundles:
        mine = [o for o in observations if o["lane"] == "persistent" and o["fixture"] == b["fixture"] and o["impl"] == b["impl"]]
        for i, o in enumerate(mine):
            o["prepNs"] = b["prepNs"][2 * i:2 * i + 2]
    for o in observations:
        o["outputMatchesExpected"] = o["outSha256"] == expected[o["fixture"]]
        o["prepTotalNs"] = sum(o["prepNs"]) if len(o.get("prepNs", [])) == 2 else None

    return {"started": started, "finished": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
            "impls": names, "order": order, "observations": observations, "persistentBundles": bundles,
            "expectedOutputSha256": expected}


def analyze(raw: dict, names: list[str], lanes: list[str]) -> dict:
    a, b = names
    out: dict = {}
    obs = [o for o in raw["observations"] if not o["warmup"]]
    for lane in lanes:
        lane_obs = [o for o in obs if o["lane"] == lane]
        entry: dict = {}
        for name in names:
            mine = [o for o in lane_obs if o["impl"] == name]
            entry[name] = {
                "wallMs": summarize([o["wallNs"] / 1e6 for o in mine]),
                "prepMs": summarize([o["prepTotalNs"] / 1e6 for o in mine if o["prepTotalNs"] is not None]),
            }
            if lane == "fresh":
                entry[name]["maxRssBytes"] = summarize([o["maxRssBytes"] for o in mine])
                entry[name]["cpuSec"] = summarize([o["userCpuSec"] + o["sysCpuSec"] for o in mine])
            else:
                bundles = [x for x in raw["persistentBundles"] if x["impl"] == name]
                entry[name]["bundleMaxRssBytes"] = summarize([x["maxRssBytes"] for x in bundles])
                entry[name]["bundleCpuSec"] = summarize([x["userCpuSec"] + x["sysCpuSec"] for x in bundles])
        # 짝 차이: 같은 입력·같은 반복의 (a − b). 양수면 b 가 빠르다.
        pairs = {}
        for o in lane_obs:
            pairs.setdefault((o["fixture"], o["rep"]), {})[o["impl"]] = o
        diffs_wall = [p[a]["wallNs"] / 1e6 - p[b]["wallNs"] / 1e6 for p in pairs.values() if a in p and b in p]
        diffs_prep = [p[a]["prepTotalNs"] / 1e6 - p[b]["prepTotalNs"] / 1e6 for p in pairs.values()
                      if a in p and b in p and p[a]["prepTotalNs"] is not None and p[b]["prepTotalNs"] is not None]
        entry["pairedDiffMs"] = {"definition": f"{a} − {b} (양수 = {b} 가 빠름)",
                                 "wall": summarize(diffs_wall), "prep": summarize(diffs_prep),
                                 f"{b}FasterWallPairs": sum(d > 0 for d in diffs_wall), "pairs": len(diffs_wall)}
        # 입력별 중앙값(크기에 따른 차이를 보기 위해)
        per_fx = {}
        for fid in raw["order"]:
            row = {}
            for name in names:
                mine = [o for o in lane_obs if o["fixture"] == fid and o["impl"] == name]
                row[name] = {"wallMedianMs": statistics.median(o["wallNs"] / 1e6 for o in mine),
                             "prepMedianMs": statistics.median(o["prepTotalNs"] / 1e6 for o in mine)}
            per_fx[fid] = row
        entry["perFixtureMedians"] = per_fx
        entry["measuredObservations"] = len(lane_obs)
        out[lane] = entry
    out["mismatchedOutputs"] = sum(not o["outputMatchesExpected"] for o in raw["observations"])
    out["nonZeroExits"] = sum(o.get("exit", 0) != 0 for o in raw["observations"]) + sum(
        x["exit"] != 0 for x in raw["persistentBundles"])
    return out


def time_l_crosscheck(cmd: list[str], lines: list[bytes]) -> dict:
    """/usr/bin/time -l 로 한 번 더 재서 getrusage(ru_maxrss) 값과 같은 출처인지 확인한다(참고용 1회)."""
    payload = b"\n".join(lines) + b"\n"
    r = subprocess.run(["/usr/bin/time", "-l"] + cmd + ["--serve"], input=payload, capture_output=True, cwd=GEN)
    m = re.search(rb"(\d+)\s+maximum resident set size", r.stderr)
    p = re.search(rb"(\d+)\s+peak memory footprint", r.stderr)
    return {"timeLMaxRssBytes": int(m.group(1)) if m else None, "timeLPeakFootprintBytes": int(p.group(1)) if p else None,
            "exit": r.returncode}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reps", type=int, default=3)
    parser.add_argument("--warmup", type=int, default=1)
    parser.add_argument("--seed", type=int, default=309)
    parser.add_argument("--lanes", default="fresh,persistent")
    parser.add_argument("--aa", action="store_true")
    parser.add_argument("--out")
    args = parser.parse_args()
    args.lanes = args.lanes.split(",")
    tag = "aa" if args.aa else "local"
    out_path = Path(args.out) if args.out else RESULTS / f"rust-parts-pilot-{tag}-{dt.date.today():%Y%m%d}.json"

    raw = run(args)
    names = raw["impls"]
    lock = load_lock()
    cross = {}
    if not args.aa:
        big = next(fx for fx in lock["fixtures"] if fx["id"] == "aud-04")
        lines = (GEN / big["requestFile"]).read_bytes().splitlines()
        cmds = commands()
        cross = {name: time_l_crosscheck(cmds[name], lines) for name in ("python", "rust")}
        cross["fixture"] = "aud-04"
    report = {
        "label": "로컬 macOS 측정 — 운영(Oracle E2.1.Micro, Linux) 수치가 아님",
        "issue": "#312",
        "environment": environment(),
        "config": {"seed": args.seed, "reps": args.reps, "warmup": args.warmup, "lanes": args.lanes, "concurrency": 1,
                   "aa": args.aa, "observationDefinition": "collect 준비 + filter 준비 (요청 2개)",
                   "fileCacheCleared": False, "rssSource": "os.wait4 rusage ru_maxrss (macOS 는 바이트 단위)",
                   "wallDefinition": {"fresh": "Popen 직전 → wait4 직후(프로세스 시작·임포트·출력 전달 포함)",
                                      "persistent": "첫 요청 쓰기 직전 → 두 번째 응답 읽기 직후(출력 전달 포함, 시작 제외)"},
                   "prepDefinition": "CLI 안에서 요청 JSON 해석 → parts 생성(파일 읽기·base64) → 출력 JSON 직렬화. stdin 읽기·stdout 쓰기는 제외"},
        "counts": {
            "measuredObservations": sum(not o["warmup"] for o in raw["observations"]),
            "warmupObservations": sum(o["warmup"] for o in raw["observations"]),
            "freshProcesses": sum(o["lane"] == "fresh" for o in raw["observations"]),
            "persistentProcesses": len(raw["persistentBundles"]),
        },
        "summary": analyze(raw, names, args.lanes),
        "timeLCrossCheck": cross,
        "raw": raw,
    }
    out_path.write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    s = report["summary"]
    for lane in args.lanes:
        for name in names:
            e = s[lane][name]
            rss = e.get("maxRssBytes") or e.get("bundleMaxRssBytes")
            print(f"{lane:10s} {name:9s} wall 중앙값 {e['wallMs']['median']:9.3f}ms  prep 중앙값 {e['prepMs']['median']:9.3f}ms  "
                  f"최대RSS 중앙값 {rss['median'] / 1048576:7.1f}MiB (최대 {rss['max'] / 1048576:.1f})")
        d = s[lane]["pairedDiffMs"]
        print(f"{lane:10s} 짝 차이({d['definition']}) wall 중앙값 {d['wall']['median']:.3f}ms, prep 중앙값 {d['prep']['median']:.3f}ms, "
              f"{names[1]} 쪽이 빠른 짝 {d[names[1] + 'FasterWallPairs']}/{d['pairs']}")
    print(f"출력 불일치 {s['mismatchedOutputs']}, 0 아닌 종료 {s['nonZeroExits']} → {out_path}")
    return 0 if s["mismatchedOutputs"] == 0 and s["nonZeroExits"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
