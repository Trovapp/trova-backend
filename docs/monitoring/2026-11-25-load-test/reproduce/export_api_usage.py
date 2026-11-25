"""Read only synthetic clone API ledger; export aggregates, never credentials or response bodies."""
import json,subprocess
from run import C,OUT,db_calls
sql="""SELECT COALESCE(json_agg(x),'[]') FROM (
 SELECT provider,operation,success,count(*) AS metric_units,
 sum(coalesce(attempts,1)) AS attempt_units,
 count(DISTINCT created_at) AS distinct_record_times,
 min(latency_ms) AS min_ms,max(latency_ms) AS max_ms,sum(latency_ms) AS sum_ms
 FROM api_call_logs GROUP BY provider,operation,success ORDER BY provider,operation,success
) x"""
a=json.loads(subprocess.check_output(C+['exec','-T','db','psql','-U','loadtest','-d','loadtest','-tA','-c',sql],text=True))
# recordBatch uses Collections.nCopies of ONE ApiCallLog and ONE Timestamp per batch.
# Distinct created_at gives HTTP batch count, cross-checked against sanitized logs.
batches=sum(x['distinct_record_times'] for x in a if x['provider']=='gemini' and x['operation']=='place-embedding-batch')
other=sum(x['attempt_units'] for x in a if x['provider']=='gemini' and x['operation']!='place-embedding-batch')
usage={'ledger':db_calls(),'operations':a,'gemini_batch_http_calls':batches,'gemini_other_attempts':other,'gemini_http_attempts':batches+other,'method':'batch record created_at deduplication (ApiCallLogJdbcRepository uses one Timestamp), others sum coalesce(attempts,1); compare app logs','kakao_confirmed_dependency_calls':1,'kakao_additional_interrupted_attempts_range':[0,1]}
assert usage['ledger']['places']<=2000,'Places budget exceeded'
(OUT/'external-usage.json').write_text(json.dumps(usage,ensure_ascii=False,indent=2))
print(json.dumps({k:v for k,v in usage.items() if k not in ['ledger','operations']},ensure_ascii=False))
