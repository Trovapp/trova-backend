"""Export evidence, capture local Grafana, then delete only this disposable project."""
import json,os,subprocess,time,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent;OUT=HERE.parent/'raw';ROOT=HERE.parents[3];PYTHON='/private/tmp/trova-534-report-venv/bin/python'
for _ in range(2400):
 if (OUT/'valid-policy-completed').exists():break
 time.sleep(2)
else:raise RuntimeError('measurements not completed')
for script in ['export_api_usage.py','collect.py','summarize.py']:
 subprocess.run([sys.executable,str(HERE/script)],check=True)
env=dict(os.environ,MPLCONFIGDIR='/private/tmp/trova-534-mpl')
subprocess.run([PYTHON,str(ROOT/'docs/monitoring/scripts/plot_load_test.py')],check=True,env=env)
try:subprocess.run([PYTHON,str(HERE/'capture_grafana.py')],check=True)
except subprocess.CalledProcessError:(OUT/'grafana-capture-unavailable.txt').write_text('Headless capture failed; exported Prometheus JSON and PNG charts are preserved.\n')
D='/usr/local/bin/docker';C=[D,'compose','-p','trova-loadtest-534','-f',str(HERE/'compose.yml'),'--profile','visual','--profile','probe']
subprocess.run(C+['down','-v','--remove-orphans','-t','5'],check=True)
result={'at':time.time(),'containers':subprocess.check_output([D,'ps','-a','--filter','label=com.docker.compose.project=trova-loadtest-534','--format','{{.Names}}'],text=True).strip(),'networks':subprocess.check_output([D,'network','ls','--filter','label=com.docker.compose.project=trova-loadtest-534','--format','{{.Name}}'],text=True).strip(),'volumes':subprocess.check_output([D,'volume','ls','--filter','label=com.docker.compose.project=trova-loadtest-534','--format','{{.Name}}'],text=True).strip()}
assert not any(result[k] for k in ['containers','networks','volumes'])
result['key_file_preserved']=Path('/private/tmp/claude-501/-Users-gimtaehyeong-orca-taeng-agent-manager/93d69a51-105f-4d3f-8331-3d49c93b1a04/scratchpad/loadtest-keys/external-api.env').exists()
(OUT/'cleanup.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print('컨테이너·볼륨·네트워크 정리 완료; 키 파일은 보존',flush=True)
# Repository CLAUDE.md requires a full build before commit. Run after load has ended.
validation_env={k:v for k,v in os.environ.items() if k not in ['GEMINI_API_KEY','GOOGLE_PLACES_API_KEY','KAKAO_REST_API_KEY','DATABASE_URL','DB_URL','PGPASSWORD','PGHOST','PGUSER','PGDATABASE'] and not k.startswith('SPRING_DATASOURCE_')}
validation_env['JAVA_HOME']='/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home'
start=time.time()
with (OUT/'gradle-build.log').open('w') as f:
 p=subprocess.run([str(ROOT/'gradlew'),'--no-daemon','--max-workers=1','-Dorg.gradle.jvmargs=-Xmx768m -XX:ActiveProcessorCount=2','-I',str(HERE/'test-resources.gradle'),'build'],cwd=ROOT,env=validation_env,stdout=f,stderr=subprocess.STDOUT)
(OUT/'gradle-build-result.json').write_text(json.dumps({'start':start,'end':time.time(),'exit':p.returncode,'max_workers':1,'gradle_heap_mib':768,'test_heap_mib':1024,'active_processor_count':2},indent=2))
print('전체 Gradle build 종료 코드:',p.returncode,flush=True)
(OUT/'evidence-and-build-completed').write_text(str(time.time()))
