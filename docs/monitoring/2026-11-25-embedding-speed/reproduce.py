#!/usr/bin/env python3
"""격리된 로컬 I/O 가짜 재생. Gemini/Places/운영 DB 호출 없음. Python 표준 라이브러리만 사용."""
import argparse
from datetime import datetime
import re
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
REPORT = Path(__file__).resolve().parent
PACKAGE = "com/trova/backend/recommendation"
TEST = "com.trova.backend.recommendation.EmbeddingSpeedTest"
SOURCE = ROOT / "src/main/java" / PACKAGE / "PlaceEmbeddingService.java"


def gradle(args, log):
    with (REPORT / log).open("w") as output:
        code = subprocess.run(["./gradlew", "--no-daemon", *args], cwd=ROOT,
                              stdout=output, stderr=subprocess.STDOUT).returncode
    path = REPORT / log
    path.write_text("\n".join(line.rstrip() for line in path.read_text().splitlines()) + "\n")
    return code



def analyze_journal():
    source = ROOT / "docs/monitoring/2026-11-25-v11-recheck-followup/server-alternatives/journal.log"
    rows = source.read_text().splitlines()
    used, matches = set(), []
    for index, line in enumerate(rows):
        if "대안 찾기 구간별 시간:" not in line:
            continue
        thread = re.search(r"--- \[([^]]+)\]", line)[1]
        timestamp = datetime.fromisoformat(line.split()[0])
        for previous in range(index - 1, -1, -1):
            batch = rows[previous]
            if "장소 임베딩 묶음:" in batch and re.search(r"--- \[([^]]+)\]", batch)[1] == thread:
                gap = (timestamp - datetime.fromisoformat(batch.split()[0])).total_seconds()
                matches.append(dict(stage_line=index + 1, batch_line=previous + 1, gap_seconds=gap,
                                    batch_size=int(re.search(r"묶음: (\d+)개", batch)[1])))
                if gap < 1:
                    used.add(previous)
                break
    extras = [int(re.search(r"묶음: (\d+)개", line)[1]) for i, line in enumerate(rows)
              if "장소 임베딩 묶음:" in line and i not in used]
    result = dict(source=str(source.relative_to(ROOT)),
                  matching="같은 스레드의 가장 가까운 이전 묶음과 단계행의 간격 1초 미만; 요청 ID 없는 로그이므로 상관 관계 추정",
                  matched=len(used), extra_batch_sizes=extras, matches=matches)
    (REPORT / "journal-analysis.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mutations", action="store_true")
    args = parser.parse_args()
    analyze_journal()
    generated = ROOT / "build/tmp/t46-baseline"
    generated_package = generated / PACKAGE
    generated_package.mkdir(parents=True, exist_ok=True)
    for name in ("PlaceEmbeddingService", "AlternativeFinderService"):
        original = subprocess.check_output(["git", "show", f"0b35d6c:src/main/java/{PACKAGE}/{name}.java"], cwd=ROOT, text=True)
        for other in ("PlaceEmbeddingService", "AlternativeFinderService"):
            original = original.replace(other, "Before" + other)
        (generated_package / ("Before" + name + ".java")).write_text(original)
    init = generated / "replay.gradle"
    init.write_text("""allprojects {
        plugins.withId('java') {
            sourceSets.test.java.srcDir('build/tmp/t46-baseline')
            tasks.named('test') {
                systemProperty 't46.baseline', 'true'
                systemProperty 't46.report', file('docs/monitoring/2026-11-25-embedding-speed/timings.csv').absolutePath
            }
        }
    }
    """)
    code = gradle(["-I", str(init), "test", "--tests", TEST], "replay.log")
    if code:
        raise SystemExit("원본 비교 실패: replay.log 확인")
    if not args.mutations:
        return
    original = SOURCE.read_text()
    mutations = [
        ("묶음_분할", "MAX_BATCH_SIZE = 100", "MAX_BATCH_SIZE = 1",
         "기존_결과_점수_이유_벡터_대상과_한번의_묶음을_유지한다"),
        ("순차_복원", """                if (from == 0) {
                    independentWork.run();
                }
                GeneratedBatch generated;""", """                GeneratedBatch generated;""",
         "Gemini_HTTP와_독립작업이_겹치고_DB는_요청스레드에_남는다")
    ]
    results = []
    try:
        for label, old, new, method in mutations:
            assert old in original
            mutated = original.replace(old, new, 1)
            if label == "순차_복원":
                mutated = mutated.replace("                saveBatch(batch,", "                if (from == 0) independentWork.run();\n                saveBatch(batch,", 1)
            SOURCE.write_text(mutated)
            code = gradle(["test", "--tests", TEST + "." + method], label + ".log")
            xml = ROOT / "build/test-results/test" / ("TEST-" + TEST + ".xml")
            failures = []
            if xml.exists():
                failures = [t.attrib["name"] for t in ET.parse(xml).findall(".//testcase") if t.find("failure") is not None]
            caught = code != 0 and any(method in f for f in failures)
            results.append(dict(mutation=label, exit_code=code, caught=caught, failed_tests=failures))
            if not caught:
                raise RuntimeError("변형 검사 미검출: " + label)
    finally:
        SOURCE.write_text(original)
        (REPORT / "mutations.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
    if gradle(["test", "--tests", TEST, "--tests", "*PlaceEmbeddingServiceTest", "--tests", "*AlternativeFinderServiceTest"], "restored.log"):
        raise SystemExit("복원 후 테스트 실패")


if __name__ == "__main__":
    main()
