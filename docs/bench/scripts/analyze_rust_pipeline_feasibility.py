"""Recalculate existing production evidence offline; never runs a video or API call."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import statistics

OPERATIONS = {"extract_places.collect", "extract_places.filter", "select_place_match", "verify_places"}
PRIMARY = {"extract_places.collect", "extract_places.filter"}


def number(value, name, positive=False):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f"Missing or non-finite {name}")
    if value < 0 or (positive and value == 0):
        raise ValueError(f"Invalid {name}")
    return float(value)


def describe(values):
    return {"n": len(values), "min": round(min(values), 3), "median": round(statistics.median(values), 3),
            "mean": round(statistics.mean(values), 3), "max": round(max(values), 3)}


def analyze(document):
    if document.get("complete") is not True or document.get("errors"):
        raise ValueError("Only completed historical runs without run errors are accepted")
    rows, warmups, identities = [], 0, set()
    for job in document["results"]:
        if not isinstance(job.get("warmup"), bool):
            raise ValueError("Missing explicit warmup flag")
        if job["warmup"]:
            warmups += 1
            continue
        mode = job.get("mode")
        if mode not in {"async", "kafka"} or job.get("observedPath") != mode or job.get("status") != "DONE":
            raise ValueError("Invalid path/status in historical sample")
        identity = job["jobId"]
        if identity in identities:
            raise ValueError("Duplicate jobId")
        identities.add(identity)
        duration = number(job.get("processingSeconds"), "processingSeconds", positive=True) * 1000
        queue = number(job.get("queueWaitMs"), "queueWaitMs")
        total = number(job.get("createdToAnalysisDoneSeconds"), "createdToAnalysisDoneSeconds", positive=True) * 1000
        if abs(duration + queue - total) > 1:
            raise ValueError("Processing duration includes inconsistent queue accounting")
        calls = [call for call in job["apiLogRows"] if call.get("provider") == "gemini"]
        for operation in PRIMARY:
            if sum(call.get("operation") == operation for call in calls) != 1:
                raise ValueError("Expected one successful collect and filter log per sample")
        seen_operations = set()
        for call in calls:
            operation = call.get("operation")
            if operation not in OPERATIONS or operation in seen_operations or call.get("success") is not True:
                raise ValueError("Unexpected, repeated or failed Gemini operation; review sequentiality before summing")
            seen_operations.add(operation)
            number(call.get("latencyMs"), "Gemini latencyMs")
        primary = sum(call["latencyMs"] for call in calls if call["operation"] in PRIMARY)
        all_ai = sum(call["latencyMs"] for call in calls)
        if all_ai > duration + 1:
            raise ValueError("Summed call windows exceed processing time; do not clamp or infer CPU time")
        attempts = [call.get("attempts") for call in calls]
        waits = [call.get("waitedMs") for call in calls]
        for attempt in attempts:
            if attempt is not None and (isinstance(attempt, bool) or not isinstance(attempt, int) or attempt < 1):
                raise ValueError("Invalid attempts")
        for wait in waits:
            if wait is not None:
                number(wait, "waitedMs")
        rows.append({"jobId": identity, "mode": mode, "videoId": job["videoId"],
                     "processingMs": round(duration, 3), "queueWaitMs": queue,
                     "primaryCallWindowMs": primary, "allGeminiCallWindowMs": all_ai,
                     "primaryCallSharePercent": 100 * primary / duration,
                     "allGeminiCallSharePercent": 100 * all_ai / duration,
                     "outsideGeminiLogWindowsMs": round(duration - all_ai, 3),
                     "outsideGeminiLogWindowsIsCpuTime": False,
                     "geminiLogicalLogs": len(calls),
                     "knownAttemptSum": sum(value for value in attempts if value is not None),
                     "unknownAttemptRows": attempts.count(None),
                     "knownWaitedMs": sum(value for value in waits if value is not None),
                     "unknownWaitedRows": waits.count(None)})
    if not rows:
        raise ValueError("No measured samples")
    groups = {}
    for mode in sorted({row["mode"] for row in rows}):
        group = [row for row in rows if row["mode"] == mode]
        groups[mode] = {"n": len(group), "uniqueVideos": len({row["videoId"] for row in group}),
                        **{name: describe([row[name] for row in group]) for name in [
                            "processingMs", "queueWaitMs", "primaryCallWindowMs", "allGeminiCallWindowMs",
                            "primaryCallSharePercent", "allGeminiCallSharePercent", "outsideGeminiLogWindowsMs"]}}
    return {"scope": "Historical Python production context, not a Python/Rust A/B experiment",
            "recordedSourceCommit": document.get("sourceCommit"),
            "recordedStartedAt": document.get("startedAt"), "recordedFinishedAt": document.get("finishedAt"),
            "warmupsExcluded": warmups, "measuredJobs": len(rows), "byMode": groups, "samples": rows,
            "availability": {"pythonCpuTimeMs": None, "pythonPeakRssBytes": None,
                             "mediaPreparationWallMs": None, "perJobMediaStageTimings": None,
                             "rustAfterResults": None, "rustSpeedupEstimate": None},
            "limitations": [
                "Three YouTube videos; direct-link path expected from measured-source code, media timings not captured in this JSON",
                "No Instagram media processing evidence; FFmpeg and Python-local CPU share cannot be inferred",
                "Gemini log windows include network, remote processing, local response parsing and any retry waits; not pure external time",
                "Summing Gemini windows assumes the reviewed sequential collect/filter/select/verify flow; no concurrent Kakao log sum is subtracted",
                "Outside Gemini log windows also includes geocoding, DB, Java, subprocess/log overhead; not Python CPU or a Rust speedup bound",
                "Stored Java RSS after drafts is not Python worker memory and is intentionally excluded",
                "Current source differs from historical run; do not label these figures current production performance",
                "No Rust implementation ran; before/after improvement remains unmeasured"],
            "illustrativeScenariosNotMeasurements": [
                {"assumedLocalCpuShare": share, "assumedLocalSpeedup": 10,
                 "totalTimeReductionPercent": round(100 * share * (1 - 1 / 10), 3)}
                for share in [0.01, 0.05, 0.10]]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=Path(__file__).resolve().parents[1] / "results/production-queue-expanded-20261101.json")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.input.resolve() == args.output.resolve():
        raise ValueError("Output must not overwrite historical evidence")
    data = args.input.read_bytes()
    result = analyze(json.loads(data))
    result["sourceFile"] = args.input.name
    result["sourceSha256"] = hashlib.sha256(data).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"jobs": result["measuredJobs"], "rustAfterMeasured": False, "output": str(args.output)}))


if __name__ == "__main__":
    main()
