#!/usr/bin/env python3
"""Bounded real evaluation; reads private key explicitly, keeps human gold/scoring unchanged.
No server/DB. Outputs public test video IDs/names and sanitized metrics, not key/error payloads.
"""
import argparse
import contextlib
import csv
import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'pipeline-test'))
import extract_places as extraction
import run_pipeline


class StopEvaluation(BaseException):
    pass


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--resume',action='store_true')
    parser.add_argument('--candidate',type=Path,default=Path(__file__).with_name('experiments')/'grounded-names-v1.json')
    args=parser.parse_args()
    if args.output.exists() and not args.resume:raise SystemExit('Output exists; use --resume only for verified transient failures')
    if args.resume and not args.output.exists():raise SystemExit('No partial output to resume')
    for line in args.env_file.read_text().splitlines():
        if line.startswith('GEMINI_API_KEY='):
            os.environ['GEMINI_API_KEY']=line.split('=',1)[1].strip().strip('"').strip("'")
    if not os.environ.get('GEMINI_API_KEY'):raise SystemExit('GEMINI_API_KEY missing')
    os.environ.update(PIPELINE_TRACE='1',PIPELINE_FILTER_AUDIT='0',PIPELINE_FILTER_TEXT_ONLY='0',PROMPTFOO_CACHE_ENABLED='false')
    extraction.FILTER_TEXT_ONLY=False
    candidate=json.loads(args.candidate.read_text())
    original_collect,original_filter=extraction.COLLECT_PROMPT,extraction.FILTER_PROMPT
    videos=list(csv.DictReader((ROOT/'eval/extraction/videos.csv').open()))
    gold=list(csv.DictReader((ROOT/'eval/extraction/answers.csv').open()))
    cases=[next(row for row in videos if row['video_no']==str(i)) for i in range(1,11)]
    result={'scope':'10 same human-gold YouTube cases; paired alternating before/after; no cache; pre-geocoding names only',
            'startedAt':datetime.datetime.now().astimezone().isoformat(),'model':extraction.DEFAULT_MODEL,
            'maxAttemptsPerPhase':30,'maxAttemptsTotal':60,'minimumRequestGapSeconds':8,
            'attemptsStarted':{'before':0,'after':0},'results':[],'completed':False,
            'sourceHashes':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in
                [ROOT/'pipeline-test/extract_places.py',ROOT/'pipeline-test/run_pipeline.py',ROOT/'eval/extraction/answers.csv',ROOT/'eval/extraction/videos.csv',ROOT/'eval/place-extract/scoring.cjs',args.candidate.resolve()]}}
    if args.resume:
        previous=json.loads(args.output.read_text())
        if previous['sourceHashes']!=result['sourceHashes']:raise SystemExit('Input/prompt/scoring hashes changed')
        if previous['completed']:raise SystemExit('Completed run cannot resume')
        errors=[row for row in previous['results'] if row['status']=='error']
        if not errors or any(row.get('errorType') not in ['RemoteDisconnected','ConnectionResetError','TimeoutError'] for row in errors):
            raise SystemExit('Only explicit transient connection failures may resume, not rate-limit/budget/source errors')
        result=previous
        result.setdefault('resumptions',[]).append({'at':datetime.datetime.now().astimezone().isoformat(),
            'reason':'retry failed transient transport case; prior outcomes retained; original request budgets unchanged'})
    result.setdefault('runnerVersions',[]).append({'sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'precedingInterruptedRunnerSha256':'49f0c17c30f01c6395ac24affe323e5ae836b1c53c96f1f69175dd61270010d7' if args.resume else None})
    args.output.parent.mkdir(parents=True,exist_ok=True)
    def save():
        temp=args.output.with_suffix('.partial');temp.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');temp.replace(args.output)
    phase='before';last_request=0.0;actual_urlopen=urllib.request.urlopen
    def bounded_urlopen(request,*pos,**kw):
        nonlocal last_request
        url=request.full_url if isinstance(request,urllib.request.Request) else str(request)
        if url.startswith(extraction.API_BASE):
            if result['attemptsStarted'][phase]>=30 or sum(result['attemptsStarted'].values())>=60:
                raise StopEvaluation('request_budget')
            wait=8-(time.monotonic()-last_request)
            if wait>0:time.sleep(wait)
            last_request=time.monotonic();result['attemptsStarted'][phase]+=1;save()
            try:return actual_urlopen(request,*pos,**kw)
            except urllib.error.HTTPError as error:
                if error.code==429:raise StopEvaluation('rate_limit') from None
                raise
        return actual_urlopen(request,*pos,**kw)
    urllib.request.urlopen=bounded_urlopen
    node="""const fs=require('fs'),s=require(process.argv[1]);const d=JSON.parse(fs.readFileSync(0,'utf8'));
const c={vars:{expected:JSON.stringify(d.expected)}},o=JSON.stringify(d.names);
console.log(JSON.stringify({strict:s.score(o,c),legacyRecall:s.legacyRecall(o,c).score,legacyPrecision:s.legacyPrecision(o,c).score}));"""
    try:
        for index,video in enumerate(cases):
            phases=['before','after'] if index%2==0 else ['after','before']
            for phase in phases:
                if any(row['videoNo']==video['video_no'] and row['phase']==phase and row['status']=='ok' for row in result['results']):continue
                extraction.COLLECT_PROMPT=original_collect+(candidate['collect_append'] if phase=='after' else '')
                extraction.FILTER_PROMPT=original_filter+(candidate['filter_append'] if phase=='after' else '')
                expected=[row['place_name'] for row in gold if row['video_no']==video['video_no'] and row['place_name'] and row['is_destination']!='N']
                row={'videoNo':video['video_no'],'phase':phase,'expected':expected,'status':'running'}
                result['results'].append(row);save();log=io.StringIO();started=time.monotonic()
                try:
                    with contextlib.redirect_stderr(log),tempfile.TemporaryDirectory(prefix='trova-grounding-') as work:
                        try:output=run_pipeline.run(video['url'],Path(work))
                        except SystemExit as error:
                            message=str(error)
                            if message==extraction.NOT_PLACE_CONTENT_MARKER or message.startswith(extraction.NOT_PLACE_CONTENT_MARKER+' '):output={'places':[]}
                            else:raise
                    names=[''.join(place['name'].split()) for place in output['places'] if isinstance(place.get('name'),str) and place['name'].strip()]
                    row['names']=names
                    row['places']=output['places']
                    scored=subprocess.run(['node','-e',node,str(ROOT/'eval/place-extract/scoring.cjs')],input=json.dumps({'names':names,'expected':expected}),text=True,capture_output=True,check=True)
                    row['scores']=json.loads(scored.stdout);row['status']='ok'
                except BaseException as error:
                    row['status']='error';row['errorType']=type(error).__name__
                    if isinstance(error,StopEvaluation):row['stopCategory']=str(error)
                    raise
                finally:
                    row['seconds']=round(time.monotonic()-started,3);row['apiLogRows']=[];row['trace']=[]
                    for line in log.getvalue().splitlines():
                        if line.startswith('TROVA_API_LOG:'):
                            data=json.loads(line.split(':',1)[1]);row['apiLogRows'].append({k:v for k,v in data.items() if k in ['operation','success','latencyMs','attempts','waitedMs','promptTokens','responseTokens','totalTokens']})
                        elif line.startswith('TROVA_EXTRACTION_TRACE:'):
                            data=json.loads(line.split(':',1)[1]);row['trace'].append({k:v for k,v in data.items() if k in ['stage','candidates','places','unmatchedCandidates']})
                    save()
                print('video',video['video_no'],phase,'strict',row['scores']['strict']['recall'],row['scores']['strict']['precision'],flush=True)
        result['completed']=True
    finally:
        urllib.request.urlopen=actual_urlopen
        extraction.COLLECT_PROMPT,extraction.FILTER_PROMPT=original_collect,original_filter
        result['finishedAt']=datetime.datetime.now().astimezone().isoformat();save()
    print(args.output,flush=True)


if __name__=='__main__':
    main()
