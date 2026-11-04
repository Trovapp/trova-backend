#!/usr/bin/env python3
"""#319: 배포(a8c04d0) 뒤 운영에서 유튜브 1건·인스타 1건을 테스트 계정(user 1)으로 분석해
Python 자원(#315)과 Gemini 생각·캐시·영상 토큰(#318)을 모은다. 큐·설정·JAR은 바꾸지 않는다.
테스트로 생긴 장소·자동 초안은 백업(0600) 후 지우고, 작업·API 기록은 남긴다.
"""
import argparse
import datetime
import hashlib
import json
import re
import subprocess
import time
import urllib.request
from pathlib import Path

from production_queue_bench import Bench

URLS = [('youtube', 'https://www.youtube.com/shorts/8vmpPuFwQ94'),
        ('instagram', 'https://www.instagram.com/reel/DX-rrm8BHCr/')]
API_COLUMNS = ['provider', 'operation', 'success', 'latency_ms', 'attempts', 'waited_ms', 'prompt_tokens',
               'response_tokens', 'total_tokens', 'thoughts_tokens', 'cached_tokens', 'video_tokens']


def journal_lines(args, job):
    out = subprocess.run(['ssh', '-i', args.ssh_key, '-o', 'BatchMode=yes', args.host,
                          'sudo journalctl -u trova-backend --since -20min --no-pager -o cat'],
                         capture_output=True, text=True, timeout=60).stdout
    pattern = re.compile(r'ProcessingJob %d (Python 자원|단계 시간|Java 단계 시간|분석 실행 시간|파이프라인 완료).*' % job)
    return [m.group(0) for m in pattern.finditer(out)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-production', action='store_true', required=True)
    parser.add_argument('--kinds', default='youtube,instagram', help='돌릴 종류(쉼표). #325는 instagram만')
    parser.add_argument('--marker-prefix', default='trova-319')
    for field in ['env-file', 'host', 'ssh-key', 'base-url', 'output', 'backup-dir', 'source-commit']:
        parser.add_argument('--' + field, required=True)
    args = parser.parse_args()
    args.local_requests = 0
    bench = Bench(args)
    bench.marker = args.marker_prefix + '-' + bench.marker.rsplit('-', 1)[-1]
    bench.result.update(scope='운영 1회씩(유튜브·인스타) 자원·토큰 기준값. 속도 비교 아님',
                        harnessSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
    try:
        bench.healthy()
        kinds = args.kinds.split(',')
        for kind, url in [u for u in URLS if u[0] in kinds]:
            bench.result['preflightInFlight'] = bench.idle()
            shares = bench.query("select count(*) from processing_jobs where user_id=1 and created_at >= "
                                 "date_trunc('day',now() at time zone 'Asia/Seoul')")[0][0]
            gemini_today = bench.query("select count(*) from api_call_logs where provider='gemini' and created_at >= "
                                       "date_trunc('day',now() at time zone 'Asia/Seoul')")[0][0]
            if shares >= 18 or gemini_today > 150:
                raise RuntimeError('한도 여유 부족 — 중단')
            started = time.monotonic()
            request = urllib.request.Request(args.base_url + '/api/shares',
                                             data=json.dumps({'url': url, 'reanalyze': True}).encode(),
                                             headers={'Authorization': 'Bearer ' + bench.token, 'Content-Type': 'application/json'})
            with urllib.request.urlopen(request, timeout=30) as response:
                job = json.load(response)['jobId']
            row = {'kind': kind, 'url': url, 'jobId': job, 'sharesBefore': shares, 'geminiRowsTodayBefore': gemini_today}
            bench.result['results'].append(row)
            bench.save()
            print('created', kind, 'job', job, flush=True)
            deadline = time.monotonic() + 400
            while time.monotonic() < deadline:
                status, created, finished = bench.query('select coalesce(analysis_status,status),created_at,analysis_finished_at '
                                                        'from processing_jobs where id=%s and user_id=1', (job,))[0]
                if status in ('DONE', 'FAILED'):
                    break
                time.sleep(2)
            else:
                raise TimeoutError('분석이 끝나지 않음')
            row.update(status=status, createdAt=str(created), analysisFinishedAt=str(finished),
                       clientWaitSeconds=round(time.monotonic() - started, 3))
            if finished:
                row['createdToAnalysisDoneSeconds'] = (finished - created).total_seconds()
            bench.idle()
            time.sleep(3)
            row['apiLogRows'] = [dict(zip(API_COLUMNS, r)) for r in bench.query(
                'select ' + ','.join(API_COLUMNS) + ' from api_call_logs where job_id=%s order by id', (job,))]
            row['journal'] = journal_lines(args, job)
            row['savedPlaceCount'] = bench.query('select count(*) from saved_places where user_id=1 and processing_job_id=%s', (job,))[0][0]
            bench.save()
            print('done', kind, status, row.get('createdToAnalysisDoneSeconds'), flush=True)
            if status != 'DONE':
                print('분석 실패 — 다음 건은 그대로 진행(한도 사용 1건 추가)', flush=True)
            time.sleep(5)
        bench.result['complete'] = True
    except BaseException as error:
        bench.result['errors'].append({'stage': 'measurement', 'type': type(error).__name__, 'message': str(error)[:300]})
        raise
    finally:
        try:
            bench.cleanup()
        finally:
            bench.result['finishedAt'] = datetime.datetime.now().astimezone().isoformat()
            bench.save()


if __name__ == '__main__':
    main()
