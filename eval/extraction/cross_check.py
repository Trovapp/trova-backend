"""정답 세트 확장용 교차 판정(#437) — 같은 입력을 Gemini(파이프라인)와 GPT(Codex)에 따로 넣고 결과를 비교한다.

단계(모두 WSL/리눅스에서 pipeline-test 모듈을 그대로 쓴다):
  prepare  : candidates.csv의 영상을 내려받아 입력 묶음(제목·게시물 설명·자막·오디오·프레임 8장)을 만든다. Gemini 호출 없음.
  gemini   : 입력 묶음을 extract_places()에 넣는다(운영 다운로드 경로와 같은 함수). 429·하루 한도가 보이면 바로 멈춘다.
  gpt-prompt: GPT(Codex)에 넘길 지시문(자막·설명·제목만, 키·오디오 없음)을 샘플마다 만든다. 실행은 Codex가 한다.
  compare  : gemini/·gpt/ 결과를 장소 이름으로 맞춰 compare.csv(전체), mismatches.csv(사람 확인용), agreed.csv(answers.csv 형식)를 쓴다.

입력 묶음(영상·프레임·자막)은 저작물이라 pipeline-test/work/cross/ 아래(gitignore)에만 두고, 레포에는 추출 결과 JSON과 CSV만 남긴다.

사용 예(WSL):
  python3 eval/extraction/cross_check.py prepare  --run eval/extraction/cross/20261116 --ids c001,c002
  python3 eval/extraction/cross_check.py gemini   --run eval/extraction/cross/20261116 --ids c001,c002
  python3 eval/extraction/cross_check.py gpt-prompt --run eval/extraction/cross/20261116 --ids c001,c002
  python3 eval/extraction/cross_check.py compare  --run eval/extraction/cross/20261116
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import subprocess
import sys
import time
import unicodedata
from difflib import SequenceMatcher
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
PIPELINE = REPO / "pipeline-test"
CANDIDATES = HERE / "cross" / "candidates.csv"
CANDIDATES_PATH = [CANDIDATES]  # --candidates로 바꾼다(사람 정답 기준선 human_baseline.csv 등)
WORK = PIPELINE / "work" / "cross"

# Gemini 쪽 결과에서 "멈춰야 하는" 신호 — 재시도 로그(429)나 하루 한도 표식.
STOP_PATTERNS = ("Gemini 429", "DAILY_QUOTA", "하루 한도", "RESOURCE_EXHAUSTED")
GPT_TRANSCRIPT_LIMIT = 6000  # 지시문이 너무 길어지지 않게 자막은 앞부분만


# ---------- 공통 ----------

def load_candidates(ids: set[str] | None = None) -> list[dict]:
    with open(CANDIDATES_PATH[0], newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    return [r for r in rows if not ids or r["sample_id"] in ids]


def parse_ids(value: str | None) -> set[str] | None:
    return {v.strip() for v in value.split(",") if v.strip()} if value else None


def input_dir(sample_id: str) -> Path:
    return WORK / sample_id


# ---------- prepare ----------

def youtube_fetch(url: str, out_dir: Path, resolution_sort: str) -> tuple[Path, Path | None, dict]:
    """영상·한국어 자막·메타데이터를 yt-dlp 한 번으로 받는다.

    파이프라인의 download.download는 영어(자동 번역) 자막까지 받는데, 연달아 받으면 유튜브가 자막에 429를 줘서
    영상까지 실패했다(2026-11-16 실측, 5개 중 4개). 정답 세트 입력은 한국어 자막만 받고, 자막이 실패해도
    영상은 받도록 --ignore-errors를 건다.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    for stale in out_dir.iterdir():
        stale.unlink()
    proc = subprocess.run(
        ["yt-dlp", "--no-warnings", "--ignore-errors", "--no-simulate", "--dump-single-json",
         "-f", "bv*+ba/b", "-S", resolution_sort, "--write-auto-sub", "--write-sub", "--sub-lang", "ko",
         "--sub-format", "vtt", "-o", str(out_dir / "video.%(ext)s"), "--", url],
        capture_output=True, text=True)
    videos = sorted((p for p in out_dir.glob("video.*") if p.suffix not in (".vtt", ".json", ".part")),
                    key=lambda p: p.stat().st_size, reverse=True)
    if not videos:
        raise SystemExit(f"yt-dlp 다운로드 실패: {proc.stderr.strip().splitlines()[-1:] or ''}")
    captions = sorted(out_dir.glob("video.*.vtt"))
    try:
        data = json.loads(proc.stdout.strip().splitlines()[-1]) if proc.stdout.strip() else {}
    except json.JSONDecodeError:
        data = {}
    return videos[0], captions[0] if captions else None, data


def prepare_one(row: dict) -> dict:
    sys.path.insert(0, str(PIPELINE))
    import download  # noqa: E402
    import frames as frames_mod  # noqa: E402
    import run_pipeline  # noqa: E402

    url = row["url"]
    out = input_dir(row["sample_id"])
    out.mkdir(parents=True, exist_ok=True)
    description = None
    title = row.get("title") or None
    caption_path = None
    if run_pipeline.INSTAGRAM_URL.match(url):
        video_path, info_title, description, _images = run_pipeline.instagram_fetch(url, out / "download")
        title = info_title or title
        if video_path is None:
            raise SystemExit("사진 게시물은 이번 세트에서 빼고 영상만 쓴다")
    else:
        video_path, caption_path, data = youtube_fetch(url, out / "download", download.MAX_RESOLUTION_SORT)
        title = data.get("title") or title
        description = (data.get("description") or "").strip() or None
    transcript = download.vtt_to_text(caption_path) if caption_path else None
    audio = None
    if run_pipeline.has_audio_stream(video_path):
        audio = run_pipeline.extract_audio(video_path, out / "audio.mp3")
    frame_paths = frames_mod.extract_frames(video_path, out / "frames", max_frames=run_pipeline.MAX_FRAMES)
    bundle = {
        "sample_id": row["sample_id"], "url": url, "title": title, "description": description,
        "transcript": transcript, "audio": str(audio) if audio else None,
        "frames": [str(p) for p in frame_paths],
    }
    (out / "input.json").write_text(json.dumps(bundle, ensure_ascii=False, indent=2), encoding="utf-8")
    return bundle


def cmd_prepare(args) -> None:
    for row in load_candidates(parse_ids(args.ids)):
        if (input_dir(row["sample_id"]) / "input.json").exists():
            continue
        started = time.monotonic()
        try:
            bundle = prepare_one(row)
            print(f"{row['sample_id']} ok frames={len(bundle['frames'])} transcript={bool(bundle['transcript'])} "
                  f"audio={bool(bundle['audio'])} {time.monotonic() - started:.0f}s", flush=True)
        except (SystemExit, Exception) as exc:  # 한 영상이 실패해도 다음 영상은 계속
            print(f"{row['sample_id']} FAIL {str(exc)[:200]}", flush=True)
            failed = input_dir(row["sample_id"])
            failed.mkdir(parents=True, exist_ok=True)
            (failed / "prepare_error.txt").write_text(str(exc)[:2000], encoding="utf-8")
        time.sleep(args.pause / 2)  # 유튜브 자막·영상 요청 속도 제한(429) 보호


# ---------- gemini ----------

def gemini_one(sample_id: str) -> None:
    """한 샘플 — 별도 프로세스에서 불려 stderr를 부모가 읽는다."""
    sys.path.insert(0, str(PIPELINE))
    from extract_places import extract_places  # noqa: E402

    bundle = json.loads((input_dir(sample_id) / "input.json").read_text(encoding="utf-8"))
    places = extract_places(
        transcript=bundle["transcript"],
        audio_path=Path(bundle["audio"]) if bundle["audio"] else None,
        frame_paths=[Path(p) for p in bundle["frames"]] or None,
        post_description=bundle["description"],
    )
    print(json.dumps(places, ensure_ascii=False))


def should_stop(stderr: str) -> bool:
    return any(p in stderr for p in STOP_PATTERNS)


def cmd_gemini(args) -> None:
    run = Path(args.run)
    (run / "gemini").mkdir(parents=True, exist_ok=True)
    for row in load_candidates(parse_ids(args.ids)):
        sid = row["sample_id"]
        target = run / "gemini" / f"{sid}.json"
        if target.exists() or not (input_dir(sid) / "input.json").exists():
            continue
        started = time.monotonic()
        proc = subprocess.run([sys.executable, str(Path(__file__).resolve()), "gemini-one", sid],
                              capture_output=True, text=True, cwd=PIPELINE)
        seconds = round(time.monotonic() - started, 1)
        stderr = proc.stderr
        result = {"sample_id": sid, "url": row["url"], "seconds": seconds, "exit": proc.returncode,
                  "gemini429": "Gemini 429" in stderr}
        if proc.returncode == 0:
            result["status"] = "ok"
            result["places"] = json.loads(proc.stdout.strip().splitlines()[-1])
        elif "NOT_PLACE_CONTENT" in stderr:
            result["status"] = "not_place_content"
            result["places"] = []
        else:
            result["status"] = "error"
            result["error"] = stderr.strip().splitlines()[-1][:300] if stderr.strip() else ""
        if result["status"] != "error":
            target.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"{sid} {result['status']} places={len(result.get('places', []))} {seconds}s "
              f"429={result['gemini429']} {result.get('error', '')}", flush=True)
        if should_stop(stderr):
            (run / "gemini" / "STOPPED.txt").write_text(
                f"{sid}에서 429/한도 신호를 보고 멈춤\n{stderr[-1500:]}", encoding="utf-8")
            print(f"STOP: {sid}에서 429/한도 신호 — 나머지는 돌리지 않는다", flush=True)
            return
        time.sleep(args.pause)


# ---------- gpt-prompt ----------

GPT_INSTRUCTIONS = """너는 여행 영상에서 사용자가 실제로 방문할 수 있는 장소를 뽑는 평가자다.
첨부 이미지(영상 프레임)와 아래 텍스트(제목·게시물 설명·자막)만 근거로 한다. 웹 검색·추측 금지.
- 장소: 영상이 소개하거나 방문한 가게·관광지·숙소·시장·해변 등 지도에서 찾을 수 있는 고유 이름.
- 빼기: 지역명만(예: "부산"), 지하철역·공항은 그 자체가 소개 대상일 때만 넣기, 브랜드 일반명, 음식 이름.
- 이름은 영상에 나온 표기 그대로(한국어 우선), 지점명이 보이면 포함.
- 장소를 소개하는 영상이 아니면 is_place_content=false, places=[].
출력은 JSON 하나만: {"is_place_content": true, "places": [{"name": "...", "region": "시/구 또는 도시", "category": "restaurant|cafe|attraction|lodging|shopping|other", "evidence": "frame|transcript|title|description"}]}
"""


def gpt_prompt(bundle: dict) -> str:
    transcript = (bundle.get("transcript") or "")[:GPT_TRANSCRIPT_LIMIT]
    return (GPT_INSTRUCTIONS
            + f"\n[제목]\n{bundle.get('title') or '(없음)'}\n"
            + f"\n[게시물 설명]\n{bundle.get('description') or '(없음)'}\n"
            + f"\n[자막]\n{transcript or '(없음)'}\n")


def cmd_gpt_prompt(args) -> None:
    for row in load_candidates(parse_ids(args.ids)):
        path = input_dir(row["sample_id"]) / "input.json"
        if not path.exists():
            continue
        bundle = json.loads(path.read_text(encoding="utf-8"))
        (input_dir(row["sample_id"]) / "gpt_prompt.txt").write_text(gpt_prompt(bundle), encoding="utf-8")
        print(row["sample_id"])


# ---------- compare ----------

_PUNCT = re.compile(r"[\s\-_·•.,'\"’`!?()\[\]{}/&|~:;@#]+")
_BRANCH = re.compile(r"(본점|직영점)$")


def normalize(name: str) -> str:
    text = unicodedata.normalize("NFKC", name or "").lower()
    text = re.sub(r"\(.*?\)", "", text)
    text = _PUNCT.sub("", text)
    return _BRANCH.sub("", text)


def same_place(a: str, b: str) -> bool:
    na, nb = normalize(a), normalize(b)
    if not na or not nb:
        return False
    if na == nb:
        return True
    shorter, longer = sorted((na, nb), key=len)
    if len(shorter) >= 3 and shorter in longer:
        return True
    return SequenceMatcher(None, na, nb).ratio() >= 0.85


def names_of(place: dict) -> list[str]:
    names = [place.get("name") or ""]
    names += [n for n in place.get("nameCandidates") or [] if isinstance(n, str)]
    return [n for n in names if n.strip()]


def match_places(gemini: list[dict], gpt: list[dict]) -> list[tuple[dict | None, dict | None]]:
    """이름(대안 철자 포함)으로 1:1 짝을 짓는다. 짝이 없는 쪽은 None."""
    pairs: list[tuple[dict | None, dict | None]] = []
    used: set[int] = set()
    for g in gemini:
        hit = None
        for i, o in enumerate(gpt):
            if i in used:
                continue
            if any(same_place(a, b) for a in names_of(g) for b in names_of(o)):
                hit = i
                break
        if hit is None:
            pairs.append((g, None))
        else:
            used.add(hit)
            pairs.append((g, gpt[hit]))
    pairs += [(None, o) for i, o in enumerate(gpt) if i not in used]
    return pairs


def load_result(path: Path) -> dict | None:
    if not path.exists():
        return None
    data = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(data, list):  # GPT 결과가 장소 배열만 있을 때
        return {"places": data}
    return data


def cmd_compare(args) -> None:
    run = Path(args.run)
    rows = {r["sample_id"]: r for r in load_candidates()}
    compare_rows, mismatch_rows, agreed_rows, summary = [], [], [], []
    for gem_path in sorted((run / "gemini").glob("c*.json")):
        sid = gem_path.stem
        gem = load_result(gem_path)
        gpt = load_result(run / "gpt" / f"{sid}.json")
        if gpt is None:
            continue
        info = rows.get(sid, {})
        pairs = match_places(gem.get("places") or [], gpt.get("places") or [])
        both = sum(1 for g, o in pairs if g and o)
        for g, o in pairs:
            status = "both" if g and o else "gemini_only" if g else "gpt_only"
            row = {"sample_id": sid, "url": info.get("url", ""), "title": info.get("title", ""), "status": status,
                   "gemini_name": (g or {}).get("name", ""), "gpt_name": (o or {}).get("name", ""),
                   "region": (g or {}).get("region") or (o or {}).get("region") or info.get("region", ""),
                   "category": (g or {}).get("category") or (o or {}).get("category") or ""}
            compare_rows.append(row)
            if status == "both":
                agreed_rows.append({"video_no": sid, "place_name": row["gemini_name"], "region": row["region"],
                                    "address_or_kakao_url": "", "shown_as": "", "is_destination": "Y",
                                    "note": f"cross:gemini+gpt(gpt={row['gpt_name']})"})
            else:
                mismatch_rows.append({**row, "human_verdict": "", "human_name": ""})
        union = len(pairs)
        summary.append({"sample_id": sid, "gemini": len(gem.get("places") or []), "gpt": len(gpt.get("places") or []),
                        "both": both, "union": union,
                        "agreement": round(both / union, 3) if union else 1.0,
                        "gemini_status": gem.get("status", ""),
                        "gpt_is_place_content": gpt.get("is_place_content", "")})
    write_csv(run / "compare.csv", compare_rows)
    write_csv(run / "mismatches.csv", mismatch_rows)
    write_csv(run / "agreed.csv", agreed_rows)
    write_csv(run / "summary.csv", summary)
    total_both = sum(s["both"] for s in summary)
    total_union = sum(s["union"] for s in summary)
    report = {"samples": len(summary), "places_both": total_both, "places_union": total_union,
              "place_agreement": round(total_both / total_union, 3) if total_union else None,
              "samples_fully_agreed": sum(1 for s in summary if s["agreement"] == 1.0),
              "gemini_only": sum(1 for r in compare_rows if r["status"] == "gemini_only"),
              "gpt_only": sum(1 for r in compare_rows if r["status"] == "gpt_only")}
    (run / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


def cmd_score_human(args) -> None:
    """GPT 판정자 점검 — 사람이 만든 answers.csv(영상 번호 = sample_id의 숫자)와 GPT 결과를 같은 이름 맞추기로 비교한다."""
    run = Path(args.run)
    answers: dict[str, list[dict]] = {}
    with open(HERE / "answers.csv", newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r["is_destination"].strip().upper() == "Y":
                answers.setdefault(f"v{int(r['video_no']):03d}", []).append({"name": r["place_name"]})
    rows, totals = [], {"answers": 0, "gpt": 0, "matched": 0}
    for path in sorted((run / "gpt").glob("v*.json")):
        sid = path.stem
        gpt = load_result(path).get("places") or []
        truth = answers.get(sid, [])
        pairs = match_places(truth, gpt)
        matched = sum(1 for t, o in pairs if t and o)
        for t, o in pairs:
            rows.append({"sample_id": sid, "status": "both" if t and o else "answer_only" if t else "gpt_only",
                         "answer_name": (t or {}).get("name", ""), "gpt_name": (o or {}).get("name", "")})
        totals["answers"] += len(truth)
        totals["gpt"] += len(gpt)
        totals["matched"] += matched
    write_csv(run / "human_compare.csv", rows)
    report = {"videos": len({r["sample_id"] for r in rows}), **totals,
              "recall": round(totals["matched"] / totals["answers"], 3) if totals["answers"] else None,
              "precision": round(totals["matched"] / totals["gpt"], 3) if totals["gpt"] else None}
    (run / "human_summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


def write_csv(path: Path, rows: list[dict]) -> None:
    with open(path, "w", newline="", encoding="utf-8") as f:
        if not rows:
            f.write("")
            return
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("prepare", "gemini", "gpt-prompt", "compare", "score-human"):
        p = sub.add_parser(name)
        p.add_argument("--run", default=str(HERE / "cross" / "run"))
        p.add_argument("--candidates", default=str(CANDIDATES), help="샘플 목록 CSV(sample_id,url,title 열 필요)")
        p.add_argument("--ids", help="샘플 ID 몇 개만(쉼표)")
        p.add_argument("--pause", type=float, default=8.0, help="Gemini 샘플 사이 대기(초) — 분당 한도 보호")
    one = sub.add_parser("gemini-one")
    one.add_argument("sample_id")
    args = ap.parse_args()
    if args.cmd == "gemini-one":
        gemini_one(args.sample_id)
        return
    CANDIDATES_PATH[0] = Path(args.candidates)
    {"prepare": cmd_prepare, "gemini": cmd_gemini, "gpt-prompt": cmd_gpt_prompt, "compare": cmd_compare,
     "score-human": cmd_score_human}[args.cmd](args)


if __name__ == "__main__":
    main()
