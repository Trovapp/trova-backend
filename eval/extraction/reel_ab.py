"""#350 릴스 내려받기 해상도·프레임 디코딩 변경 전후의 장소 추출 비교(로컬 Mac, Gemini 무료 티어).

평가 세트(videos.csv)의 인스타그램 릴스를 실제 파이프라인(run_pipeline.run)으로 두 번 돌린다.
- before: 내려받기 res:720, 전체 디코딩 스레드 기본값(#350 전)
- after : 지금 코드(res:540, 전체 디코딩 -threads 1)
영상마다 before/after 순서를 번갈아(ABBA) 시간대 영향을 줄인다. Gemini는 영상·변형마다 1회.
찾은 장소 이름을 answers.csv와 맞춰(공백 제거 후 포함 관계) 재현율을 비교한다. 지오코딩·DB·서버는 쓰지 않는다.

사용: GEMINI_API_KEY=... python3 eval/extraction/reel_ab.py --out <결과 JSON> [--gap 20]
"""
from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
PIPELINE = HERE.parent.parent / "pipeline-test"

WORKER = r'''
import json, sys
from pathlib import Path
sys.path.insert(0, sys.argv[1])
import download, frames, run_pipeline
from stage_timer import StageTimer
variant, url, work = sys.argv[2], sys.argv[3], Path(sys.argv[4])
if variant == "before":
    download.MAX_RESOLUTION_SORT = "res:720"
    original = frames._full_decode_command
    def no_threads(*a, **k):
        cmd = original(*a, **k)
        i = cmd.index("-threads")
        return cmd[:i] + cmd[i + 2:]
    frames._full_decode_command = no_threads
timer = StageTimer()
out = run_pipeline.run(url, work, timer)
video = next((p for p in (work / "download").glob("video.*")), None)
size = None
if video is not None:
    import subprocess
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                            "stream=codec_name,width,height", "-of", "csv=p=0", str(video)], capture_output=True, text=True)
    size = probe.stdout.strip()
print("RESULT " + json.dumps({"places": [p.get("name") for p in out.get("places", [])], "timings": timer.as_dict(),
                              "cpu": timer.cpu_dict(), "video": size}, ensure_ascii=False))
'''


def norm(name: str) -> str:
    return "".join((name or "").split()).lower()


def matched(found: list[str], answer: str) -> bool:
    a = norm(answer)
    return any(a and f and (a in f or f in a) for f in map(norm, found))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--gap", type=float, default=20.0)
    ap.add_argument("--only", help="영상 번호 몇 개만(쉼표)")
    args = ap.parse_args()
    with open(HERE / "videos.csv", newline="") as f:
        reels = [r for r in csv.DictReader(f) if r["platform"].startswith("instagram") and r["selected"].upper() == "Y"]
    if args.only:
        keep = set(args.only.split(","))
        reels = [r for r in reels if r["video_no"] in keep]
    answers: dict[str, list[str]] = {}
    with open(HERE / "answers.csv", newline="") as f:
        for r in csv.DictReader(f):
            answers.setdefault(r["video_no"], []).append(r["place_name"])
    results = []
    for k, reel in enumerate(reels):
        order = ["before", "after"] if k % 2 == 0 else ["after", "before"]
        row = {"video_no": reel["video_no"], "url": reel["url"], "answers": answers.get(reel["video_no"], [])}
        for variant in order:
            if results or variant != order[0]:
                time.sleep(args.gap)
            with tempfile.TemporaryDirectory(prefix="reel-ab-") as work:
                p = subprocess.run([sys.executable, "-c", WORKER, str(PIPELINE), variant, reel["url"], work],
                                   capture_output=True, text=True, timeout=600)
            line = next((x for x in p.stdout.splitlines() if x.startswith("RESULT ")), None)
            res = json.loads(line[7:]) if line else {"error": p.stderr.strip()[-300:]}
            if line:
                res["hits"] = sum(matched(res["places"], a) for a in row["answers"])
            row[variant] = res
            print(json.dumps({"video": reel["video_no"], "variant": variant, "hits": res.get("hits"),
                              "of": len(row["answers"]), "video_info": res.get("video"),
                              "framesCpu": res.get("cpu", {}).get("framesChildCpuMs"),
                              "error": res.get("error")}, ensure_ascii=False), flush=True)
        results.append(row)
    Path(args.out).write_text(json.dumps(results, indent=2, ensure_ascii=False))
    total = {v: sum(r[v].get("hits", 0) for r in results) for v in ("before", "after")}
    print(json.dumps({"answers": sum(len(r["answers"]) for r in results), **total}), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
