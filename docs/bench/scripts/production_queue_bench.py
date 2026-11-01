#!/usr/bin/env python3
"""Explicit production benchmark: owned override/data cleanup, no JAR or permanent env changes.
Run with the existing operator venv (psycopg) and --run-production; see dated spec/report.
"""
import argparse
import base64
import datetime
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time
import urllib.error
import urllib.request
import uuid

import psycopg

VIDEOS = ['H1bCCd1vDCA', 'YS4z0NqKkyw', '8vmpPuFwQ94']
SCRIPT_NAMES = ['extract_places.py', 'run_pipeline.py', 'generate_itinerary.py', 'verify_places.py',
                'select_place_match.py', 'tag_places.py', 'summarize_reviews.py']


class Bench:
    def __init__(self, args):
        self.args = args
        self.env = {}
        for line in Path(args.env_file).read_text().splitlines():
            if '=' in line and not line.startswith('#'):
                key, value = line.split('=', 1)
                self.env[key] = value.strip().strip('"').strip("'")
        self.address = re.search(r'//([^:/]+)(?::(\d+))?/([^?]+)', self.env['SPRING_DATASOURCE_URL'])
        self.output = Path(args.output)
        self.output.parent.mkdir(parents=True, exist_ok=True)
        if self.output.exists():
            raise ValueError('Output already exists; keep historical evidence')
        self.marker = 'trova-bench-288-' + uuid.uuid4().hex[:12]
        self.drop = '/run/systemd/system/trova-backend.service.d/90-' + self.marker + '.conf'
        self.override = '/run/' + self.marker + '.env'
        self.conf_text = '[Service]\n# ' + self.marker + '\nEnvironmentFile=' + self.override + '\n'
        self.override_attempted = False
        now = int(time.time())
        b64 = lambda value: base64.urlsafe_b64encode(value).rstrip(b'=')
        body = b64(b'{"alg":"HS256","typ":"JWT"}') + b'.' + b64(json.dumps({'sub': '1', 'iat': now, 'exp': now + 7200}).encode())
        self.token = (body + b'.' + b64(hmac.new(self.env['JWT_SECRET'].encode(), body, hashlib.sha256).digest())).decode()
        self.start_local = datetime.datetime.now().replace(tzinfo=None)
        self.result = {'startedAt': datetime.datetime.now().astimezone().isoformat(),
                       'sourceCommit': args.source_commit, 'results': [], 'transitions': [],
                       'cleanup': {}, 'restored': False, 'errors': [], 'localClientInvocations': args.local_requests,
                       'scope': 'production same JAR/Python, serial K-A-K, 3 videos, n=6 per mode; 3 warmups',
                       'temporaryDropIn': self.drop}

    def save(self):
        temp = self.output.with_suffix('.partial')
        temp.write_text(json.dumps(self.result, ensure_ascii=False, indent=2, default=str) + '\n')
        temp.replace(self.output)

    def db(self):
        return psycopg.connect(host=self.address[1], port=self.address[2] or 5432, dbname=self.address[3],
                               user=self.env['SPRING_DATASOURCE_USERNAME'], password=self.env['SPRING_DATASOURCE_PASSWORD'],
                               sslmode='require', prepare_threshold=None)

    def query(self, sql, params=()):
        with self.db() as connection:
            connection.execute('SET TRANSACTION READ ONLY')
            connection.execute("SET LOCAL statement_timeout='15000'")
            return connection.execute(sql, params).fetchall()

    def remote(self, script, config=None, timeout=100):
        code = 'cfg=' + repr(config or {}) + '\n' + script
        response = subprocess.run(['ssh', '-i', self.args.ssh_key, '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15',
                                   self.args.host, 'sudo python3 -'], input=code, text=True,
                                  capture_output=True, timeout=timeout)
        if response.returncode:
            raise RuntimeError('Remote action failed; raw stderr withheld')
        return json.loads(response.stdout)

    def state(self, job=None):
        return self.remote(r'''
import pathlib,subprocess,json,hashlib,re,collections
root=pathlib.Path('/opt/trova-backend')
def run(args):return subprocess.check_output(args,text=True).strip()
pid=run(['systemctl','show','trova-backend','--property=MainPID','--value'])
env=dict(x.split('=',1) for x in pathlib.Path('/proc/'+pid+'/environ').read_bytes().decode().split('\0') if '=' in x)
status=pathlib.Path('/proc/'+pid+'/status').read_text()
result={'service':run(['systemctl','is-active','trova-backend']),'queueType':env.get('APP_QUEUE_TYPE'),
'autoDraftEnabled':env.get('AUTO_DRAFT_ENABLED','true'),'quotaSharesPerDay':int(env.get('QUOTA_SHARES_PER_DAY','20')),'dropInPaths':run(['systemctl','show','trova-backend','--property=DropInPaths','--value']),
'jarSha256':hashlib.sha256((root/'app.jar').read_bytes()).hexdigest(),
'pythonHashes':{n:hashlib.sha256((root/'pipeline-test'/n).read_bytes()).hexdigest() for n in cfg['scripts']},
'rssKiB':int(re.search(r'^VmRSS:\s+(\d+)',status,re.M)[1]),'pid':int(pid),
'diagnosticFlags':{k:env.get(k,'') for k in ['PIPELINE_TRACE','PIPELINE_FILTER_AUDIT','PIPELINE_FILTER_TEXT_ONLY']}}
if cfg.get('job') is not None:
 logs=run(['journalctl','-u','trova-backend','--since='+cfg['since'],'--no-pager','-o','cat'])
 matches=re.findall(r'ProcessingJob '+str(cfg['job'])+r' 파이프라인 시작\((\w+), 대기 (\d+)ms\)',logs)
 assert len(matches)==1
 result['observedPath']=matches[0][0];result['queueWaitMs']=int(matches[0][1])
 result['warnErrorLevelEntries']=dict(collections.Counter(re.findall(r'\s(WARN|ERROR)\s+\d+\s+---',logs)))
print(json.dumps(result))
''', {'scripts': SCRIPT_NAMES, 'job': job, 'since': datetime.datetime.fromisoformat(self.result['startedAt']).astimezone(datetime.timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')})

    def idle(self):
        deadline = time.monotonic() + 420
        notice = 0
        while time.monotonic() < deadline:
            row = self.query("select (select count(*) from processing_jobs where status in ('PENDING','PROCESSING')), "
                             "(select count(*) from trip_drafts where status in ('PENDING','PROCESSING')), "
                             "(select count(*) from trip_replan_jobs where status in ('PENDING','PROCESSING'))")[0]
            if sum(row) == 0:
                return list(row)
            if time.monotonic() > notice:
                print('waiting for owned and global work to drain', flush=True)
                notice = time.monotonic() + 20
            time.sleep(2)
        raise TimeoutError('In-flight work did not drain; do not restart')

    def healthy(self):
        started = time.monotonic()
        notice = 0
        while time.monotonic() - started < 270:
            try:
                with urllib.request.urlopen(self.args.base_url + '/actuator/health', timeout=5) as response:
                    if json.load(response).get('status') == 'UP':
                        return round(time.monotonic() - started, 3)
            except Exception:
                pass
            if time.monotonic() > notice:
                print('waiting for health UP', round(time.monotonic() - started), 's', flush=True)
                notice = time.monotonic() + 20
            time.sleep(3)
        raise TimeoutError('Health did not return UP')

    def budget(self):
        known, missing = self.query("select coalesce(sum(attempts),0),count(*) filter(where attempts is null) "
                                    "from api_call_logs where provider='gemini' and created_at >= %s", (self.start_local,))[0]
        # Unknown Java log rows get the current application retry ceiling (3) for a conservative guard,
        # not a claim about observed HTTP requests. Other unlogged use is still unknown.
        estimated = int(known) + 3 * missing + self.args.local_requests
        if estimated > 170:
            raise RuntimeError('Additional call budget reserve reached')
        return {'knownAttempts': int(known), 'unknownAttemptRows': missing, 'guardEstimateIncludingLocal': estimated}

    def measure(self, block, mode, video, repetition, warmup=False):
        self.idle()
        self.result['lastBudgetCheck'] = self.budget()
        shares = self.query("select count(*) from processing_jobs where user_id=1 and created_at >= "
                            "date_trunc('day',now() at time zone 'Asia/Seoul')")[0][0]
        if shares >= 20:
            raise RuntimeError('Daily share quota reached')
        started = time.monotonic()
        request = urllib.request.Request(self.args.base_url + '/api/shares',
                                         data=json.dumps({'url': 'https://www.youtube.com/shorts/' + video, 'reanalyze': True}).encode(),
                                         headers={'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json'})
        self.result['unconfirmedSubmissionPossible'] = True
        self.save()
        with urllib.request.urlopen(request, timeout=30) as response:
            data = json.load(response)
            assert response.status == 202
        job = data['jobId']
        self.result['unconfirmedSubmissionPossible'] = False
        row = {'block': block, 'mode': mode, 'videoId': video, 'repetition': repetition, 'warmup': warmup,
               'jobId': job, 'requestMs': round((time.monotonic() - started) * 1000, 3)}
        self.result['results'].append(row)
        self.save()
        print('created', block, mode, 'warmup' if warmup else video, 'job', job, flush=True)
        deadline = time.monotonic() + 380
        while time.monotonic() < deadline:
            status, created, finished = self.query('select coalesce(analysis_status,status),created_at,analysis_finished_at '
                                                   'from processing_jobs where id=%s and user_id=1', (job,))[0]
            if status in ('DONE', 'FAILED'):
                row.update(status=status, createdAt=str(created), analysisFinishedAt=str(finished))
                if finished:
                    row['createdToAnalysisDoneSeconds'] = (finished - created).total_seconds()
                break
            time.sleep(2)
        else:
            raise TimeoutError('Analysis did not finish')
        self.idle()
        state = self.state(job)
        assert state['queueType'] == state['observedPath'] == mode
        assert state['jarSha256'] == self.args.expected_jar_sha256 and state['pythonHashes'] == self.result['before']['pythonHashes']
        row.update(observedPath=state['observedPath'], queueWaitMs=state['queueWaitMs'], rssKiBAfterDrafts=state['rssKiB'], pid=state['pid'])
        if finished:
            row['processingSeconds'] = row['createdToAnalysisDoneSeconds'] - row['queueWaitMs'] / 1000
        names = ['provider','operation','success','latencyMs','attempts','waitedMs','promptTokens','responseTokens']
        row['apiLogRows'] = [dict(zip(names, data)) for data in self.query(
            'select provider,operation,success,latency_ms,attempts,waited_ms,prompt_tokens,response_tokens '
            'from api_call_logs where job_id=%s order by id', (job,))]
        self.save()
        if status != 'DONE' or any(not call['success'] or (call['attempts'] or 1) > 1 for call in row['apiLogRows'] if call['provider']=='gemini'):
            raise RuntimeError('Analysis failure or retried AI call; stop further quota use')
        print('done', job, 'seconds', row.get('createdToAnalysisDoneSeconds'), flush=True)
        time.sleep(8)

    def switch_async(self):
        self.idle()
        self.override_attempted = True
        self.save()
        self.remote('''
import pathlib,subprocess,json,os
conf=pathlib.Path(cfg['drop']);env=pathlib.Path(cfg['env'])
assert not conf.exists() and not env.exists()
conf.parent.mkdir(parents=True,exist_ok=True)
fd=os.open(env,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w') as stream:stream.write('APP_QUEUE_TYPE=async\\n')
fd=os.open(conf,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o644)
with os.fdopen(fd,'w') as stream:stream.write(cfg['conf'])
subprocess.run(['systemctl','daemon-reload'],check=True)
subprocess.run(['systemctl','restart','trova-backend'],check=True,timeout=120)
print(json.dumps({'switched':True}))
''', {'drop': self.drop, 'env': self.override, 'conf': self.conf_text}, timeout=150)
        elapsed = self.healthy()
        state = self.state()
        assert state['queueType'] == 'async' and state['jarSha256'] == self.args.expected_jar_sha256
        self.result['transitions'].append({'to': 'async', 'healthSeconds': elapsed})
        self.save()

    def restore(self):
        if self.override_attempted:
            self.idle()
            removed = self.remote('''
import pathlib,subprocess,json
conf=pathlib.Path(cfg['drop']);env=pathlib.Path(cfg['env'])
if conf.exists():assert conf.read_text()==cfg['conf'],'unowned config content'
if env.exists():assert env.read_text()=='APP_QUEUE_TYPE=async\\n','unowned env content'
exists=conf.exists() or env.exists()
conf.unlink(missing_ok=True);env.unlink(missing_ok=True)
if exists:
 subprocess.run(['systemctl','daemon-reload'],check=True)
 subprocess.run(['systemctl','restart','trova-backend'],check=True,timeout=120)
print(json.dumps({'removed':exists}))
''', {'drop': self.drop, 'env': self.override, 'conf': self.conf_text}, timeout=150)
            elapsed = self.healthy()
            self.result['transitions'].append({'to': 'kafka-original', 'removedOwnedFiles': removed['removed'], 'healthSeconds': elapsed})
            self.override_attempted = False
        state = self.state()
        assert state['service'] == 'active' and state['queueType'] == 'kafka'
        assert state['jarSha256'] == self.args.expected_jar_sha256 and not state['dropInPaths']
        assert state['pythonHashes'] == self.result['before']['pythonHashes']
        assert state['diagnosticFlags'] == self.result['before']['diagnosticFlags']
        assert state.get('autoDraftEnabled') == self.result['before'].get('autoDraftEnabled')
        self.result.update(restored=True, after=state)
        self.save()

    def cleanup(self):
        ids = [row['jobId'] for row in self.result['results']]
        if not ids:
            return
        self.idle()
        folder = Path(self.args.backup_dir)
        folder.mkdir(parents=True, exist_ok=True)
        private = folder / (self.marker + '-outputs.json')
        with self.db() as connection:
            drafts = connection.execute("select row_to_json(d) from trip_drafts d where user_id=1 and auto_created=true and job_ids=ANY(%s)",
                                        ([str(job) for job in ids],)).fetchall()
            places = connection.execute('select row_to_json(p) from saved_places p where user_id=1 and processing_job_id=ANY(%s)', (ids,)).fetchall()
            fd = os.open(private, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            with os.fdopen(fd, 'w') as stream:
                json.dump({'drafts': [row[0] for row in drafts], 'places': [row[0] for row in places]}, stream, ensure_ascii=False, default=str)
            dr = connection.execute("delete from trip_drafts where user_id=1 and auto_created=true and job_ids=ANY(%s)", ([str(job) for job in ids],)).rowcount
            pl = connection.execute('delete from saved_places where user_id=1 and processing_job_id=ANY(%s)', (ids,)).rowcount
        residual = self.query("select (select count(*) from trip_drafts where user_id=1 and auto_created=true and job_ids=ANY(%s)), "
                              "(select count(*) from saved_places where user_id=1 and processing_job_id=ANY(%s))", ([str(job) for job in ids], ids))[0]
        assert sum(residual) == 0
        self.result['cleanup'] = {'jobIds': ids, 'draftsDeleted': dr, 'placesDeleted': pl,
                                  'residualDrafts': residual[0], 'residualPlaces': residual[1],
                                  'privateBackup': str(private), 'jobAndApiLogsKept': True}
        self.result['finalInFlight'] = self.idle()
        self.save()

    def run(self):
        try:
            before = self.state()
            assert before['queueType'] == 'kafka' and before['service'] == 'active' and not before['dropInPaths']
            assert before['quotaSharesPerDay'] == 20 and before['jarSha256'] == self.args.expected_jar_sha256
            repo = Path(__file__).resolve().parents[3]
            assert before['pythonHashes'] == {name: hashlib.sha256((repo/'pipeline-test'/name).read_bytes()).hexdigest() for name in SCRIPT_NAMES}
            assert all(value in ('', '0') for value in before['diagnosticFlags'].values())
            shares = self.query("select count(*) from processing_jobs where user_id=1 and created_at >= date_trunc('day',now() at time zone 'Asia/Seoul')")[0][0]
            assert 20 - shares - 15 >= 3
            self.result.update(before=before, sharesBefore=shares,
                               harnessSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
            self.result['preflightInFlight'] = self.idle()
            self.healthy()
            self.save()
            self.measure('K1','kafka',VIDEOS[2],0,True)
            for video in VIDEOS:
                self.measure('K1','kafka',video,1)
            self.switch_async()
            self.measure('A','async',VIDEOS[2],0,True)
            for video in reversed(VIDEOS):
                self.measure('A','async',video,1)
            for video in VIDEOS:
                self.measure('A','async',video,2)
            self.restore()
            self.measure('K2','kafka',VIDEOS[2],0,True)
            for video in reversed(VIDEOS):
                self.measure('K2','kafka',video,2)
            self.result['finalJournalWarnErrorLevelEntries'] = self.state(self.result['results'][-1]['jobId'])['warnErrorLevelEntries']
            names = ['provider','operation','rows','failedRows','knownAttempts','unknownAttemptRows','knownWaitedMs']
            self.result['apiWindowRows'] = [dict(zip(names,row)) for row in self.query(
                "select provider,operation,count(*),count(*) filter(where not success),sum(attempts),"
                "count(*) filter(where attempts is null),sum(waited_ms) from api_call_logs where created_at >= %s "
                "group by provider,operation order by provider,operation", (self.start_local,))]
            self.result['sharesAfter'] = self.query("select count(*) from processing_jobs where user_id=1 and created_at >= date_trunc('day',now() at time zone 'Asia/Seoul')")[0][0]
            self.result['complete'] = True
        except BaseException as error:
            self.result['errors'].append({'stage': 'measurement', 'type': type(error).__name__})
            print('measurement stopped:', type(error).__name__, flush=True)
            raise
        finally:
            if 'before' in self.result:
                for name, action in [('restore', self.restore), ('cleanup', self.cleanup)]:
                    try:
                        action()
                    except BaseException as error:
                        self.result['errors'].append({'stage': name, 'type': type(error).__name__})
                        if name == 'restore':
                            self.result['restored'] = False
                        print(name, 'requires attention:', type(error).__name__, flush=True)
            self.result['finishedAt'] = datetime.datetime.now().astimezone().isoformat()
            self.save()
        if self.result['errors']:
            raise RuntimeError('Benchmark has incomplete recovery/cleanup; inspect sanitized evidence')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-production', action='store_true', required=True)
    for field in ['env-file','host','ssh-key','base-url','output','backup-dir','expected-jar-sha256','source-commit','local-client-result']:
        parser.add_argument('--' + field, required=True)
    args = parser.parse_args()
    local = json.loads(Path(args.local_client_result).read_text())
    assert local['samplesPerClient'] == 20 and local['pauseMillis'] >= 8000
    assert len(local['results']) == 42 and all(row['success'] for row in local['results'])
    args.local_requests = len(local['results'])
    bench = Bench(args)
    def interrupted(signum, frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, interrupted)
    bench.run()
    print('Benchmark complete, Kafka restored, owned data backed up/removed', flush=True)


if __name__ == '__main__':
    main()
