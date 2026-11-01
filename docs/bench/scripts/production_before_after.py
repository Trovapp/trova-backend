#!/usr/bin/env python3
"""One explicitly requested production share; never switches queue or deploys.
Use once per phase, preserve output, back up/remove only this job's test outputs.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import signal
from production_queue_bench import Bench, VIDEOS


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-production', action='store_true', required=True)
    parser.add_argument('--phase', choices=['before', 'after'], required=True)
    for field in ['env-file','host','ssh-key','base-url','output','backup-dir','expected-jar-sha256','source-commit']:
        parser.add_argument('--'+field, required=True)
    args = parser.parse_args()
    args.local_requests = 0
    bench = Bench(args)
    bench.marker = 'trova-operational-296-'+args.phase+'-'+bench.marker.rsplit('-',1)[-1]
    bench.result.update(scope='One production Kafka share per phase; functional/latency sample, not a speed proof',
                        phase=args.phase, harnessSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                        sharedHarnessSha256=hashlib.sha256(Path(__file__).with_name('production_queue_bench.py').read_bytes()).hexdigest())
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    try:
        state=bench.state()
        assert state['service']=='active' and state['queueType']=='kafka' and not state['dropInPaths']
        assert state['jarSha256']==args.expected_jar_sha256
        assert all(value in ('','0') for value in state['diagnosticFlags'].values())
        shares=bench.query("select count(*) from processing_jobs where user_id=1 and created_at >= date_trunc('day',now() at time zone 'Asia/Seoul')")[0][0]
        assert shares <= (17 if args.phase=='before' else 18), 'Preserve one spare share; stop if concurrent use'
        bench.result.update(before=state, sharesBefore=shares)
        bench.result['preflightInFlight']=bench.idle()
        bench.healthy()
        bench.save()
        bench.measure(args.phase,'kafka',VIDEOS[2],1)
        job=bench.result['results'][0]['jobId']
        bench.result['results'][0]['savedPlaceNames']=[r[0] for r in bench.query('select place_name from saved_places where user_id=1 and processing_job_id=%s order by id',(job,))]
        assert bench.result['results'][0]['savedPlaceNames'], 'Expected concrete destinations'
        bench.result.update(complete=True, after=bench.state(job))
    except BaseException as error:
        bench.result['errors'].append({'stage':'measurement','type':type(error).__name__})
        raise
    finally:
        try: bench.cleanup()
        except BaseException as error:
            bench.result['errors'].append({'stage':'cleanup','type':type(error).__name__})
            raise
        finally:
            bench.result['finishedAt']=datetime.datetime.now().astimezone().isoformat()
            bench.save()
    print('Production single sample complete; owned outputs backed up/removed; evidence',args.output,flush=True)


if __name__=='__main__':
    main()
