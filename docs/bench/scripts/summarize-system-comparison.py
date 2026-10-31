#!/usr/bin/env python3
"""Recalculate existing measurements only; no network, credentials, or API calls."""
import hashlib
import json
import math
from pathlib import Path
import statistics

ROOT = Path(__file__).resolve().parents[1]
NAMES = ["client-latency-20261031.json", "local-queue-latency-20261031.json",
         "production-queue-ab-20261031.json"]
inputs = {name: json.loads((ROOT / "results" / name).read_text()) for name in NAMES}


def stats(values):
    ordered = sorted(values)
    return {"n": len(ordered), "median": statistics.median(ordered),
            "mean": statistics.mean(ordered)}


clients = {}
for client in ["rest", "spring-ai"]:
    selected = [row for row in inputs[NAMES[0]]["results"]
                if not row["warmup"] and row["client"] == client]
    assert len(selected) == 4 and all(row["success"] for row in selected)
    clients[client] = stats([row["milliseconds"] for row in selected])

local = {}
for mode in ["async", "kafka"]:
    selected = [row["milliseconds"] for row in inputs[NAMES[1]]["results"]
                if not row["warmup"] and row["path"] == mode]
    assert len(selected) == 20
    local[mode] = stats(selected)
    local[mode]["sample_p90_nearest_rank"] = sorted(selected)[math.ceil(len(selected) * .9) - 1]

production = inputs[NAMES[2]]
assert production["restored"] and production["jarHashBefore"] == production["jarHashAfter"]
assert production["cleanup"]["placesRemaining"] == production["cleanup"]["draftsRemaining"] == 0
assert len(production["results"]) == 11
assert all(row["status"] == "DONE" and row["observedPath"] == row["mode"] for row in production["results"])
operating = {}
for mode in ["async", "kafka"]:
    selected = [row for row in production["results"] if not row["warmup"] and row["mode"] == mode]
    assert len(selected) == 4
    metrics = {key: stats([row[key] for row in selected]) for key in
               ["queueWaitMs", "requestMs", "processingSeconds", "createdToAnalysisDoneSeconds", "rssKiBAfterDrafts"]}
    calls = [call for row in selected for call in row["geminiCalls"]]
    assert all(call["success"] and call["attempts"] is not None and call["waitedMs"] is not None for call in calls)
    metrics.update(logical_calls=len(calls), known_attempts=sum(call["attempts"] for call in calls),
                   known_waited_ms=sum(call["waitedMs"] for call in calls), job_ids=[row["jobId"] for row in selected])
    operating[mode] = metrics

result = {"scope": "Recalculation of dated evidence; no new performance experiment",
          "source_sha256": {name: hashlib.sha256((ROOT / "results" / name).read_bytes()).hexdigest() for name in NAMES},
          "client_latency_ms": clients, "local_delivery_ms": local, "production": operating,
          "limitations": ["Client and production samples n=4 per mode; no inferential speed or failure-rate claim",
                          "Production K-A-K blocks, one URL, serial low load; rest baseline server not deployed",
                          "RSS uncontrolled across restarts; historical JAR sizes do not isolate dependency effects",
                          "Deployment smoke and mixed 24h observations excluded"]}
output = ROOT / "results" / "system-comparison-summary-20261101.json"
output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
print(json.dumps(result, ensure_ascii=False, indent=2))
