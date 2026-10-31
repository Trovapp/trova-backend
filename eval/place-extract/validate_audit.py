"""실제 유튜브 3건의 기본/진단 모드 비교. API 사용량 확인 후에만 실행.

python3 eval/place-extract/validate_audit.py --env-file <검증된 pipeline .env>
정답은 answers.csv에서 읽고, 결과는 docs/eval/audit-validation-*.json에 저장한다.
"""
import argparse
import contextlib
import csv
import io
import json
import os
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'pipeline-test'))
import run_pipeline


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--env-file', type=Path, required=True)
    args = parser.parse_args()
    for line in args.env_file.read_text().splitlines():
        if line.startswith('GEMINI_API_KEY='):
            os.environ['GEMINI_API_KEY'] = line.split('=', 1)[1].strip().strip('\"').strip("'")
    if not os.environ.get('GEMINI_API_KEY'):
        raise SystemExit('검증된 키가 필요합니다')
    os.environ['PIPELINE_TRACE'] = '1'
    os.environ['PIPELINE_FILTER_TEXT_ONLY'] = '0'
    # run_pipeline import 시 결정된 텍스트 전용 옵션도 끈다.
    import extract_places
    extract_places.FILTER_TEXT_ONLY = False
    videos = list(csv.DictReader((ROOT / 'eval/extraction/videos.csv').open()))
    answers = list(csv.DictReader((ROOT / 'eval/extraction/answers.csv').open()))
    output = ROOT / 'docs/eval' / ('audit-validation-' + time.strftime('%Y%m%d-%H%M%S') + '.json')
    output.parent.mkdir(parents=True, exist_ok=True)
    rows = []
    for number in ['2', '3', '6']:
        video = next(v for v in videos if v['video_no'] == number)
        expected = [a['place_name'] for a in answers if a['video_no'] == number and a['place_name'] and a['is_destination'] != 'N']
        for audit in [False, True]:
            os.environ['PIPELINE_FILTER_AUDIT'] = '1' if audit else '0'
            stderr = io.StringIO()
            row = dict(video_no=number, title=video['title'], url=video['url'], audit=audit, expected=expected)
            start = time.monotonic()
            try:
                with contextlib.redirect_stderr(stderr), tempfile.TemporaryDirectory(prefix='trova-audit-') as work:
                    result = run_pipeline.run(video['url'], Path(work))
                row['names'] = [p['name'] for p in result['places']]
            except (Exception, SystemExit) as error:
                row['error'] = str(error)
            row['seconds'] = round(time.monotonic() - start, 3)
            row['trace'] = []
            row['api_calls'] = []
            for line in stderr.getvalue().splitlines():
                for marker, key in [('TROVA_EXTRACTION_TRACE:', 'trace'), ('TROVA_API_LOG:', 'api_calls')]:
                    if line.startswith(marker):
                        row[key].append(json.loads(line[len(marker):]))
            if audit:
                diagnostic = next((t for t in row['trace'] if t.get('stage') == 'decisions'), None)
                row['diagnosticStatus'] = diagnostic.get('status', 'unavailable') if diagnostic else 'unavailable'
            rows.append(row)
            output.write_text(json.dumps({'scope': '3 YouTube cases, baseline vs audit; model evidence not independently verified; no population-level quality claim', 'results': rows}, ensure_ascii=False, indent=2) + '\n')
            print(f"video={number} audit={audit} names={len(row.get('names', []))} seconds={row['seconds']} error={bool(row.get('error'))}", flush=True)
            if row.get('error'):
                print('실행 오류로 중단. 원본 결과를 확인하세요.', flush=True)
                raise SystemExit(1)
            time.sleep(6)
    print(output, flush=True)


if __name__ == '__main__':
    main()
