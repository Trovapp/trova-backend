#!/usr/bin/env python3
"""Read-only DB and private owned output backups; emits no credentials or draft content.
No new AI/Google/share calls. Window-only Java logs are not attributed to a job.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import psycopg


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--before',type=Path,required=True)
    parser.add_argument('--after',type=Path,required=True)
    args=parser.parse_args()
    if args.output.exists():raise SystemExit('Output exists; preserve historical evidence')
    env={}
    for line in args.env_file.read_text().splitlines():
        if '=' in line and not line.startswith('#'):
            key,value=line.split('=',1);env[key]=value.strip().strip('"').strip("'")
    address=re.search(r'//([^:/]+)(?::(\d+))?/([^?]+)',env['SPRING_DATASOURCE_URL'])
    output={'scope':'Owned backup draft metadata and window-only Java AI logs; no job attribution for null-job logs; no new external calls','phases':{}}
    with psycopg.connect(host=address[1],port=address[2] or 5432,dbname=address[3],user=env['SPRING_DATASOURCE_USERNAME'],password=env['SPRING_DATASOURCE_PASSWORD'],sslmode='require',prepare_threshold=None) as db:
        db.execute('SET TRANSACTION READ ONLY');db.execute("SET LOCAL statement_timeout='15000'")
        for phase,path in [('before',args.before),('after',args.after)]:
            raw=json.loads(path.read_text());assert raw['complete'] and not raw['errors']
            job=raw['results'][0]['jobId']
            backup=json.loads(Path(raw['cleanup']['privateBackup']).read_text())
            drafts=[]
            for draft in backup['drafts']:
                assert draft['user_id']==1 and draft['job_ids']==str(job) and draft['auto_created'] is True
                created=datetime.datetime.fromisoformat(draft['created_at']);updated=datetime.datetime.fromisoformat(draft['updated_at'])
                drafts.append({'id':draft['id'],'status':draft['status'],'createdAt':created.isoformat(),'updatedAt':updated.isoformat(),
                               'createdToLastUpdateSeconds':(updated-created).total_seconds(),'timingLimit':'Last update is not an explicit generation-finished timestamp'})
            start=datetime.datetime.fromisoformat(raw['startedAt']).replace(tzinfo=None)
            end=datetime.datetime.fromisoformat(raw['finishedAt']).replace(tzinfo=None)
            names=['operation','success','latencyMs','attempts','waitedMs']
            rows=db.execute("select operation,success,latency_ms,attempts,waited_ms from api_call_logs where provider='gemini' and job_id is null and operation like 'trip-plan.%%' and created_at between %s and %s order by id",(start,end)).fetchall()
            output['phases'][phase]={'sourceSha256':hashlib.sha256(path.read_bytes()).hexdigest(),'jobId':job,'ownedDrafts':drafts,'javaAiWindowLogs':[dict(zip(names,row)) for row in rows]}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
    print(args.output)


if __name__=='__main__':main()
