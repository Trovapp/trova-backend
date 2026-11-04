"""호환성 검사·측정 스크립트가 같이 쓰는 경로와 환경 기록(#312)."""
from __future__ import annotations

import hashlib
import inspect
import json
import platform
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT.parents[1]
GEN = ROOT / "fixtures" / "generated"
LOCK = ROOT / "fixtures" / "fixtures.lock.json"
RUST_BIN = ROOT / "target" / "release" / "rust_parts_pilot"
PY_CLI = ROOT / "python" / "parts_control.py"
RESULTS = REPO / "docs" / "bench" / "results"


def python_cmd() -> list[str]:
    return [sys.executable, str(PY_CLI)]


def rust_cmd() -> list[str]:
    return [str(RUST_BIN)]


def commands() -> dict[str, list[str]]:
    return {"python": python_cmd(), "rust": rust_cmd()}


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str | None:
    try:
        return sha256_bytes(path.read_bytes())
    except OSError:
        return None


def load_lock() -> dict:
    return json.loads(LOCK.read_text(encoding="utf-8"))


def _run(cmd: list[str]) -> str | None:
    try:
        return subprocess.run(cmd, capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def environment() -> dict:
    sys.path.insert(0, str(REPO / "pipeline-test"))
    import extract_places  # noqa: PLC0415

    cargo = str(Path.home() / ".cargo" / "bin" / "cargo")
    rustc = str(Path.home() / ".cargo" / "bin" / "rustc")
    return {
        "host": "로컬 macOS 개발 맥 — 운영(Oracle E2.1.Micro, Linux) 아님",
        "os": platform.platform(),
        "machine": platform.machine(),
        "cpu": _run(["sysctl", "-n", "machdep.cpu.brand_string"]),
        "cpuCount": _run(["sysctl", "-n", "hw.ncpu"]),
        "memBytes": _run(["sysctl", "-n", "hw.memsize"]),
        "python": sys.version.split()[0],
        "pythonExecutable": sys.executable,
        "rustc": _run([rustc, "--version"]),
        "cargo": _run([cargo, "--version"]),
        "gitHead": _run(["git", "-C", str(REPO), "rev-parse", "HEAD"]),
        "extractPlacesSha256": sha256_file(REPO / "pipeline-test" / "extract_places.py"),
        "buildPartsSourceSha256": sha256_bytes(inspect.getsource(extract_places.build_parts).encode()),
        "cargoLockSha256": sha256_file(ROOT / "Cargo.lock"),
        "rustBinarySha256": sha256_file(RUST_BIN),
        "pythonCliSha256": sha256_file(PY_CLI),
        "fixturesLockSha256": sha256_file(LOCK),
    }
