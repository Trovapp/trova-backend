#!/usr/bin/env python3
"""호환성 검사(#312): 고정 입력 24개와 실패 입력에서 Python 제어 CLI 와 Rust CLI 출력이 바이트 단위로 같은지 본다.

검사 내용
- 한 번 실행 모드(요청마다 새 프로세스)와 지속 모드(한 프로세스에 줄 단위 요청) 둘 다.
- 바이트 일치에 더해, parts 의 base64 를 풀어 원본 파일 바이트(fixtures.lock.json 해시)와 같은지, 미디어 순서가 입력 순서와 같은지.
- 실패 입력은 오류 종류·종료 코드·stdout 비어 있음(한 번 실행 모드)을 비교한다.
- 지속 모드에서 성공·실패 요청을 섞어도 세션이 끊기지 않는지(전체 출력 스트림 비교).

실행(먼저 cargo build --release --locked, make_fixtures.py):
  python3 tools/rust-parts-pilot/scripts/check_compat.py [--out docs/bench/results/rust-parts-pilot-compat-<날짜>.json]
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import GEN, RESULTS, commands, environment, load_lock, sha256_bytes  # noqa: E402


def run_serve(cmd: list[str], payload: bytes) -> subprocess.CompletedProcess:
    return subprocess.run(cmd + ["--serve"], input=payload, capture_output=True, cwd=GEN, timeout=120)


def run_once(cmd: list[str], manifest: Path) -> subprocess.CompletedProcess:
    return subprocess.run(cmd + [str(manifest)], capture_output=True, cwd=GEN, timeout=120)


def semantic_check(out: bytes, media_order: list[dict]) -> dict:
    """parts 를 풀어 미디어 순서·원본 바이트를 확인한다."""
    parts = json.loads(out)
    inline = [p["inline_data"] for p in parts if "inline_data" in p]
    problems = []
    if len(inline) != len(media_order):
        problems.append(f"inline 개수 {len(inline)} != 미디어 {len(media_order)}")
    for i, (part, media) in enumerate(zip(inline, media_order)):
        if sha256_bytes(base64.b64decode(part["data"], validate=True)) != media["sha256"]:
            problems.append(f"{i}번째 미디어 바이트 불일치({media['path']})")
    return {"partCount": len(parts), "mimeTypes": [p["mime_type"] for p in inline], "problems": problems}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(RESULTS / f"rust-parts-pilot-compat-{dt.date.today():%Y%m%d}.json"))
    args = parser.parse_args()

    lock = load_lock()
    cmds = commands()
    man_dir = GEN / "manifests"
    man_dir.mkdir(exist_ok=True)

    fixture_results = []
    all_lines: list[bytes] = []
    for fx in lock["fixtures"]:
        lines = (GEN / fx["requestFile"]).read_bytes().splitlines()
        all_lines += lines
        entry = {"id": fx["id"], "type": fx["type"], "note": fx["note"], "requests": []}
        serve = {name: run_serve(cmd, b"\n".join(lines) + b"\n") for name, cmd in cmds.items()}
        serve_lines = {name: r.stdout.splitlines(keepends=True) for name, r in serve.items()}
        for idx, (stage, line) in enumerate(zip(("collect", "filter"), lines)):
            manifest = man_dir / f"{fx['id']}-{stage}.json"
            manifest.write_bytes(line)
            once = {name: run_once(cmd, manifest) for name, cmd in cmds.items()}
            golden = once["python"].stdout
            sem = semantic_check(golden, fx["mediaOrder"]) if once["python"].returncode == 0 else {"problems": ["python 실패"]}
            req = {
                "stage": stage,
                "goldenSha256": sha256_bytes(golden),
                "goldenBytes": len(golden),
                "oneShotExit": {n: r.returncode for n, r in once.items()},
                "oneShotByteEqual": once["python"].stdout == once["rust"].stdout and once["python"].returncode == once["rust"].returncode == 0,
                "serveByteEqual": serve_lines["python"][idx:idx + 1] == serve_lines["rust"][idx:idx + 1] == [golden],
                "semantic": sem,
            }
            req["passed"] = req["oneShotByteEqual"] and req["serveByteEqual"] and not sem["problems"]
            entry["requests"].append(req)
        entry["passed"] = all(r["passed"] for r in entry["requests"])
        fixture_results.append(entry)

    failure_results = []
    fail_lines: list[bytes] = []
    for case in lock["failureCases"]:
        line = (GEN / case["requestFile"]).read_bytes()[:-1]
        fail_lines.append(line)
        manifest = man_dir / f"{case['id']}.json"
        manifest.write_bytes(line)
        once = {name: run_once(cmd, manifest) for name, cmd in cmds.items()}
        serve = {name: run_serve(cmd, line + b"\n") for name, cmd in cmds.items()}
        kinds = {}
        for name, r in serve.items():
            try:
                obj = json.loads(r.stdout)
                kinds[name] = obj["error"]["kind"] if isinstance(obj, dict) else "ok"
            except (ValueError, KeyError):
                kinds[name] = f"unparsable:{r.stdout[:40]!r}"
        expected_exit = 0 if case["expect"] == "ok" else 1
        res = {
            "id": case["id"], "expect": case["expect"], "kinds": kinds,
            "oneShotExit": {n: r.returncode for n, r in once.items()},
            "oneShotStdoutEmptyOnError": {n: (r.stdout == b"") for n, r in once.items()} if expected_exit else None,
            "serveByteEqual": serve["python"].stdout == serve["rust"].stdout,
            "oneShotByteEqual": once["python"].stdout == once["rust"].stdout,
        }
        res["passed"] = (
            res["serveByteEqual"] and res["oneShotByteEqual"]
            and kinds["python"] == kinds["rust"] == case["expect"]
            and res["oneShotExit"]["python"] == res["oneShotExit"]["rust"] == expected_exit
            and (expected_exit == 0 or all(res["oneShotStdoutEmptyOnError"].values()))
        )
        failure_results.append(res)

    # 지속 모드에서 성공·실패 요청을 섞은 긴 세션 하나: 오류 뒤에도 다음 요청을 처리해야 한다.
    mixed: list[bytes] = []
    for i, line in enumerate(all_lines):
        mixed.append(line)
        if i < len(fail_lines):
            mixed.append(fail_lines[i])
    session = {name: run_serve(cmd, b"\n".join(mixed) + b"\n") for name, cmd in cmds.items()}
    session_result = {
        "requests": len(mixed),
        "outputLines": {n: r.stdout.count(b"\n") for n, r in session.items()},
        "byteEqual": session["python"].stdout == session["rust"].stdout,
        "exit": {n: r.returncode for n, r in session.items()},
        "streamSha256": {n: sha256_bytes(r.stdout) for n, r in session.items()},
    }
    session_result["passed"] = session_result["byteEqual"] and all(v == len(mixed) for v in session_result["outputLines"].values())

    passed = sum(f["passed"] for f in fixture_results)
    report = {
        "date": dt.date.today().isoformat(),
        "scope": "로컬 macOS 호환성 검사. 운영 서버 실행·Gemini/카카오 호출 없음.",
        "environment": environment(),
        "comparison": "출력 바이트 그대로 비교(정규화 없음). 출력 규칙: UTF-8·compact·객체 키 정렬, 배열 순서 보존.",
        "summary": {
            "fixturesPassed": passed, "fixtureCount": len(fixture_results),
            "requestsCompared": sum(len(f["requests"]) for f in fixture_results),
            "failureCasesPassed": sum(f["passed"] for f in failure_results), "failureCaseCount": len(failure_results),
            "mixedSessionPassed": session_result["passed"],
        },
        "fixtures": fixture_results,
        "failureCases": failure_results,
        "mixedSession": session_result,
    }
    Path(args.out).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    s = report["summary"]
    print(f"고정 입력 {s['fixturesPassed']}/{s['fixtureCount']} 일치(요청 {s['requestsCompared']}개), "
          f"실패 입력 {s['failureCasesPassed']}/{s['failureCaseCount']}, 섞인 세션 {'통과' if s['mixedSessionPassed'] else '실패'} → {args.out}")
    for f in fixture_results:
        if not f["passed"]:
            print("  불일치:", f["id"], json.dumps(f["requests"], ensure_ascii=False)[:400])
    for f in failure_results:
        if not f["passed"]:
            print("  실패 입력 불일치:", json.dumps(f, ensure_ascii=False))
    ok = passed == len(fixture_results) and all(f["passed"] for f in failure_results) and session_result["passed"]
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
