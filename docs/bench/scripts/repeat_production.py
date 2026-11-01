#!/usr/bin/env python3
"""Seven serial production shares, separate analysis/draft observations, no runtime changes.
Explicit execution only. Uses the existing private operator venv (psycopg).
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import signal
import time
from production_queue_bench import Bench, SCRIPT_NAMES, VIDEOS


class RepeatBench(Bench):
    def __init__(self,args):
        super().__init__(args)
        self.marker='trova-repeat-303-'+self.marker.rsplit('-',1)[-1]
        for field in ['temporaryDropIn','transitions','restored']:
            self.result.pop(field,None)
        self.result.update(scope='One public video; current Kafka+Spring AI runtime; one preparation plus six measured repetitions; no historic pooling or adoption-effect claim',
                           runtimeConfigurationChanged=False, providerRemainingQuotaKnown=False, cleanups=[], phase='preflight',
                           harnessSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())

    def share_count(self):
        return self.query("select count(*) from processing_jobs where user_id=1 and created_at >= date_trunc('day',now() at time zone 'Asia/Seoul')")[0][0]

    def active_counts(self):
        return list(self.query("select (select count(*) from processing_jobs where status in ('PENDING','PROCESSING')), "
                               "(select count(*) from trip_drafts where status in ('PENDING','PROCESSING')), "
                               "(select count(*) from trip_replan_jobs where status in ('PENDING','PROCESSING'))")[0])

    def verify_runtime(self):
        state=self.state()
        assert state['service']=='active' and state['queueType']=='kafka' and not state['dropInPaths'],'runtime_configuration_changed'
        assert state['jarSha256']==self.args.expected_jar_sha256,'jar_changed'
        assert state['pid']==self.result['before']['pid'],'server_restarted'
        assert state['quotaSharesPerDay']==20,'quota_policy_changed'
        assert state['pythonHashes']==self.result['before']['pythonHashes'],'python_changed'
        assert state['diagnosticFlags']==self.result['before']['diagnosticFlags'],'diagnostics_changed'
        assert state['autoDraftEnabled']==self.result['before']['autoDraftEnabled'],'auto_draft_changed'
        return state

    def capture(self,row,start,end):
        job=row['jobId']
        row['savedPlaces']=[dict(zip(['name','hasCoordinates'],data)) for data in self.query(
            'select place_name,latitude is not null and longitude is not null from saved_places where user_id=1 and processing_job_id=%s order by id',(job,))]
        drafts=self.query("select id,status,created_at,updated_at,gemini_calls from trip_drafts where user_id=1 and auto_created=true and job_ids=%s order by id",(str(job),))
        row['drafts']=[]
        created=datetime.datetime.fromisoformat(row['createdAt'])
        finished=datetime.datetime.fromisoformat(row['analysisFinishedAt']) if row.get('analysisFinishedAt') else None
        for draft_id,status,draft_created,updated,calls in drafts:
            row['drafts'].append({'id':draft_id,'status':status,'createdAt':str(draft_created),'lastUpdatedAt':str(updated),
                'logicalGeminiCalls':calls,'analysisDoneToDraftCreatedSeconds':(draft_created-finished).total_seconds() if finished else None,
                'draftCreatedToLastUpdateSeconds':(updated-draft_created).total_seconds(),
                'shareCreatedToDraftLastUpdateSeconds':(updated-created).total_seconds(),
                'timingLimit':'READY last update proxy, not a dedicated finished timestamp'})
        keys=['operation','success','latencyMs','attempts','waitedMs']
        row['javaAiWindowLogs']=[dict(zip(keys,data)) for data in self.query(
            "select operation,success,latency_ms,attempts,waited_ms from api_call_logs where provider='gemini' and job_id is null and operation like 'trip-plan.%%' and created_at between %s and %s order by id",(start,end))]
        row['javaLogAttribution']='time window only; no direct job/draft foreign key'
        row['overlappingOtherWork']=list(self.query(
            "select (select count(*) from processing_jobs where id<>%s and created_at between %s and %s), "
            "(select count(*) from trip_drafts where not(user_id=1 and auto_created=true and job_ids=%s) and created_at between %s and %s), "
            "(select count(*) from trip_replan_jobs where created_at between %s and %s)",
            (job,start,end,str(job),start,end,start,end))[0])
        row['stageObservations']=self.remote(r'''
import subprocess,re,json
logs=subprocess.check_output(['journalctl','-u','trova-backend','--since='+cfg['since'],'--no-pager','-o','cat'],text=True)
id=re.escape(str(cfg['job']))
geo=re.findall(r'ProcessingJob '+id+r' 지오코딩 (\d+)곳: (\d+)ms',logs)
stages=re.findall(r'ProcessingJob '+id+r' 단계 시간: ([^\n]+)',logs)
print(json.dumps({'geocodingWholeStageMs':[int(x[1]) for x in geo],
'pythonStageSecondsRounded':[{name:float(value) for name,value in re.findall(r'(download|audio|frames|gemini) ([0-9.]+)초',line)} for line in stages],
'limits':'Rounded Python stage logs; geocoding calls can overlap; no isolated DB-save timer'}))
''',{'job':job,'since':start.replace(tzinfo=datetime.timezone(datetime.timedelta(hours=9))).astimezone(datetime.timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')})
        self.save()
        assert len(drafts)==1 and drafts[0][1]=='READY','auto_draft_missing_or_failed'
        assert sum(row['overlappingOtherWork'])==0,'other_work_overlapped'
        assert len(row['javaAiWindowLogs'])==drafts[0][4],'java_window_count_differs_from_owned_draft'
        assert all(log['success'] and log['attempts']==1 for log in row['javaAiWindowLogs']),'java_failure_or_retry'
        assert row['savedPlaces'],'no_saved_places'

    def cleanup_row(self,row):
        if row.get('cleanupComplete'):return
        self.idle()
        job=row['jobId']
        folder=Path(self.args.backup_dir);folder.mkdir(parents=True,exist_ok=True)
        backup=folder/(self.marker+'-job-'+str(job)+'.json')
        with self.db() as db:
            drafts=db.execute("select row_to_json(d) from trip_drafts d where user_id=1 and auto_created=true and job_ids=%s",(str(job),)).fetchall()
            places=db.execute('select row_to_json(p) from saved_places p where user_id=1 and processing_job_id=%s',(job,)).fetchall()
            fd=os.open(backup,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
            with os.fdopen(fd,'w') as stream:json.dump({'drafts':[r[0] for r in drafts],'places':[r[0] for r in places]},stream,ensure_ascii=False,default=str)
            draft_count=db.execute("delete from trip_drafts where user_id=1 and auto_created=true and job_ids=%s",(str(job),)).rowcount
            place_count=db.execute('delete from saved_places where user_id=1 and processing_job_id=%s',(job,)).rowcount
        residual=list(self.query("select (select count(*) from trip_drafts where user_id=1 and auto_created=true and job_ids=%s), "
                                 "(select count(*) from saved_places where user_id=1 and processing_job_id=%s)",(str(job),job))[0])
        assert sum(residual)==0,'owned_outputs_remain'
        self.result['cleanups'].append({'jobId':job,'draftsDeleted':draft_count,'placesDeleted':place_count,'residual':residual,'privateBackup':str(backup),'jobAndApiLogsKept':True})
        row['cleanupComplete']=True;self.save()

    def run(self):
        try:
            before=self.state()
            assert before['service']=='active' and before['queueType']=='kafka' and not before['dropInPaths'],'unexpected_runtime'
            assert before['jarSha256']==self.args.expected_jar_sha256,'unexpected_jar'
            repo=Path(__file__).resolve().parents[3]
            assert before['pythonHashes']=={name:hashlib.sha256((repo/'pipeline-test'/name).read_bytes()).hexdigest() for name in SCRIPT_NAMES},'unexpected_python'
            assert before['autoDraftEnabled']=='true' and before['quotaSharesPerDay']==20,'unexpected_policy'
            assert all(v in ('','0') for v in before['diagnosticFlags'].values()),'unexpected_diagnostics'
            shares=self.share_count()
            assert 20-shares-7>=3,'quota_not_ready'
            assert sum(self.active_counts())==0,'production_busy'
            self.result.update(before=before,sharesBefore=shares)
            self.healthy();self.save()
            for index in range(7):
                assert datetime.datetime.now(datetime.timezone.utc)<datetime.datetime.fromisoformat(self.args.submission_deadline).astimezone(datetime.timezone.utc),'submission_window_closed'
                assert sum(self.active_counts())==0,'other_work_active'
                assert self.share_count()<=16,'preserve_three_spare_shares'
                self.verify_runtime()
                budget=self.budget();assert budget['guardEstimateIncludingLocal']<60,'next_submission_budget_guard'
                start=datetime.datetime.now().replace(tzinfo=None)
                prior=self.query('select count(*) from processing_jobs where user_id=1 and source_url like %s',('%'+VIDEOS[2]+'%',))[0][0]
                count=len(self.result['results'])
                self.result['phase']='preparation' if index==0 else 'measurement';self.save()
                try:
                    super().measure('K','kafka',VIDEOS[2],index,warmup=index==0)
                    row=self.result['results'][-1];row['priorJobsMatchingVideoId']=prior
                    self.capture(row,start,datetime.datetime.now().replace(tzinfo=None))
                finally:
                    if len(self.result['results'])>count:self.cleanup_row(self.result['results'][-1])
            self.result.update(complete=True,sharesAfter=self.share_count(),finalInFlight=self.active_counts())
        except BaseException as error:
            self.result['errors'].append({'stage':self.result['phase'],'type':type(error).__name__})
            # Only fixed internal assertion tags; never store remote/HTTP/DB error messages.
            if isinstance(error,AssertionError):self.result['errors'][-1]['reason']=str(error)
            raise
        finally:
            for row in self.result['results']:
                if not row.get('cleanupComplete'):
                    try:self.cleanup_row(row)
                    except BaseException as error:self.result['errors'].append({'stage':'cleanup','jobId':row['jobId'],'type':type(error).__name__})
            if 'before' in self.result:
                try:self.result['after']=self.verify_runtime()
                except BaseException as error:self.result['errors'].append({'stage':'final_runtime_check','type':type(error).__name__})
            self.result['finishedAt']=datetime.datetime.now().astimezone().isoformat();self.save()
        if self.result['errors']:raise RuntimeError('incomplete_repeat_evidence')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-production',action='store_true',required=True)
    for name in ['env-file','host','ssh-key','base-url','output','backup-dir','expected-jar-sha256','source-commit','submission-deadline']:
        parser.add_argument('--'+name,required=True)
    args=parser.parse_args();args.local_requests=0
    signal.signal(signal.SIGTERM,lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    RepeatBench(args).run()


if __name__=='__main__':main()
