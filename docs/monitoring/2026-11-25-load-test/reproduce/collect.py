"""Export isolated Prometheus evidence and sanitized clone logs only."""
import gzip,json,re,subprocess,time,urllib.parse,urllib.request,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent))
from run import C,OUT,PROM,metrics,counts
rows=json.loads((OUT/'runs.json').read_text());start=min(r['start'] for r in rows);end=time.time()
queries={
 'rss':'host_process_memory_rss_bytes',
 'cpu':'process_cpu_usage', 'heap':'sum(jvm_memory_used_bytes{area="heap"})',
 'gc_max':'max(jvm_gc_pause_seconds_max)', 'gc_total':'sum(jvm_gc_pause_seconds_sum)',
 'db_active':'hikaricp_connections_active','db_pending':'hikaricp_connections_pending',
 'threads_busy':'tomcat_threads_busy_threads','threads_live':'jvm_threads_live_threads',
 'db_acquire_max':'hikaricp_connections_acquire_seconds_max','up':'up',
 'external':'trova_api_call_seconds_count','http':'http_server_requests_seconds_count',
 'kafka_lag':'kafka_consumer_fetch_manager_records_lag',
 'fallback':'trova_trip_events_fallback_total',
 'db_queries':'trova_db_queries_per_request_sum',
}
for label,q in queries.items():
 params=urllib.parse.urlencode({'query':q,'start':start,'end':end,'step':3})
 with urllib.request.urlopen(PROM+'/api/v1/query_range?'+params,timeout=20) as r:(OUT/('prom-'+label+'.json')).write_bytes(r.read())
logs=subprocess.check_output(C+['logs','--no-color','app'],text=True)
# Never store raw logs: URLs can contain credentials. Fixed format headers also redacted.
logs=re.sub(r'https?://[^\s"<>]+','<url>',logs)
logs=re.sub(r'(?i)((?:api[-_]?key|token|secret|authorization)\s*[=:]\s*)[^\s,;]+',r'\1<redacted>',logs)
logs=re.sub(r'AIza[0-9A-Za-z_-]{20,}','<redacted>',logs)
(OUT/'app-sanitized.log.gz').write_bytes(gzip.compress(logs.encode()))
(OUT/'final-calls.json').write_text(json.dumps(counts(metrics()),ensure_ascii=False,indent=2))
(OUT/'collection-window.json').write_text(json.dumps({'start':start,'end':end,'timezone':'UTC epoch; chart labels KST'},indent=2))
for service in ['app','db','kafka','prometheus']:
 cid=subprocess.check_output(C+['ps','-q',service],text=True).strip()
 # Only safe fields; full inspect would disclose env_file keys.
 fmt='{{json .HostConfig.NanoCpus}} {{json .HostConfig.Memory}} {{json .HostConfig.MemorySwap}} {{json .State.OOMKilled}} {{.Image}}'
 v=subprocess.check_output(['/usr/local/bin/docker','inspect','--format',fmt,cid],text=True)
 (OUT/('container-'+service+'.txt')).write_text(v)
print('Prometheus 시계열·마스킹 로그·자원 한도 저장 완료')
