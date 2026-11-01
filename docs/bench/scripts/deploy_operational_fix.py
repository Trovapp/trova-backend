#!/usr/bin/env python3
"""Explicit paired JAR/Python deployment, idle precheck, backups and rollback.
No environment/schema changes or API-generating smoke. Run before/after share separately.
"""
import argparse
import datetime
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import time
from production_queue_bench import Bench


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-production', action='store_true', required=True)
    for field in ['env-file','host','ssh-key','base-url','output','backup-dir','expected-jar-sha256','source-commit','baseline']:
        parser.add_argument('--'+field,required=True)
    args=parser.parse_args()
    args.local_requests=0
    bench=Bench(args)
    root=Path(__file__).resolve().parents[3]
    assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()==args.source_commit
    assert not subprocess.check_output(['git','status','--porcelain','--','src','pipeline-test','build.gradle'],cwd=root,text=True).strip()
    baseline=json.loads(Path(args.baseline).read_text())['before']
    old={'app.jar':baseline['jarSha256'],'pipeline-test/extract_places.py':baseline['pythonHashes']['extract_places.py']}
    unchanged={'pipeline-test/'+name:sha for name,sha in baseline['pythonHashes'].items() if name!='extract_places.py'}
    contents={'app.jar':(root/'build/libs/trova-backend-0.0.1-SNAPSHOT.jar').read_bytes(),
              'pipeline-test/extract_places.py':(root/'pipeline-test/extract_places.py').read_bytes()}
    new={name:hashlib.sha256(data).hexdigest() for name,data in contents.items()}
    release='operational-296-'+args.source_commit[:10]+'-'+time.strftime('%Y%m%d-%H%M%S')
    backup='/opt/trova-backend/releases/'+release+'/previous'
    cfg={'old':old,'new':new,'unchanged':unchanged,'backup':backup,'archive':'/tmp/'+release+'.tgz'}
    bench.result.update(scope='Paired JAR/extract_places.py deployment, no queue/env/schema changes',
                        release=release,beforeHashes=old,expectedHashes=new,unchangedHashes=unchanged,
                        backupDirectory=backup,rollback=False,deployed=False)
    prefix='''import pathlib,subprocess,json,hashlib,shutil,tarfile,tempfile,os,pwd
root=pathlib.Path('/opt/trova-backend')
def hashes(names):return {n:hashlib.sha256((root/n).read_bytes()).hexdigest() for n in names}
def install(folder,names):
 owner=pwd.getpwnam('trova')
 for name in names:
  target=root/name;temporary=target.with_name(target.name+'.release-296')
  assert not temporary.exists()
  try:
   shutil.copyfile(folder/name,temporary);os.chown(temporary,owner.pw_uid,owner.pw_gid);os.chmod(temporary,0o644)
   os.replace(temporary,target)
  finally:temporary.unlink(missing_ok=True)
'''
    rollback=prefix+'''
backup=pathlib.Path(cfg['backup'])
assert {n:hashlib.sha256((backup/n).read_bytes()).hexdigest() for n in cfg['old']}==cfg['old']
subprocess.run(['systemctl','stop','trova-backend'],check=True,timeout=120)
install(backup,cfg['old'])
assert hashes(cfg['old'])==cfg['old']
subprocess.run(['systemctl','start','trova-backend'],check=True,timeout=120)
print(json.dumps({'restored':True,'hashes':hashes(cfg['old'])}))
'''
    swap=prefix+'''
assert hashes(cfg['old'])==cfg['old'],'Baseline changed'
assert hashes(cfg['unchanged'])==cfg['unchanged'],'Other Python changed'
backup=pathlib.Path(cfg['backup']);backup.mkdir(parents=True,exist_ok=False)
payload=pathlib.Path(tempfile.mkdtemp(prefix='trova-release-296-'))
try:
 with tarfile.open(cfg['archive']) as archive:
  assert set(archive.getnames())==set(cfg['new'])
  for name in cfg['new']:
   target=payload/name;target.parent.mkdir(parents=True,exist_ok=True)
   target.write_bytes(archive.extractfile(name).read())
 assert {n:hashlib.sha256((payload/n).read_bytes()).hexdigest() for n in cfg['new']}==cfg['new']
 for name in cfg['old']:
  target=backup/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(root/name,target)
 assert {n:hashlib.sha256((backup/n).read_bytes()).hexdigest() for n in cfg['old']}==cfg['old']
 try:
  subprocess.run(['systemctl','stop','trova-backend'],check=True,timeout=120)
  install(payload,cfg['new'])
  assert hashes(cfg['new'])==cfg['new']
  subprocess.run(['systemctl','start','trova-backend'],check=True,timeout=120)
 except BaseException:
  install(backup,cfg['old'])
  subprocess.run(['systemctl','restart','trova-backend'],check=True,timeout=120)
  raise
finally:
 shutil.rmtree(payload);pathlib.Path(cfg['archive']).unlink(missing_ok=True)
print(json.dumps({'swapped':True,'hashes':hashes(cfg['new'])}))
'''
    swap_attempted=False
    try:
        before=bench.state()
        assert before['service']=='active' and before['queueType']=='kafka' and not before['dropInPaths']
        assert before['jarSha256']==old['app.jar'] and before['pythonHashes']==baseline['pythonHashes']
        assert all(v in ('','0') for v in before['diagnosticFlags'].values())
        bench.result['before']=before
        bench.result['preflightInFlight']=bench.idle()
        bench.healthy();bench.save()
        with tempfile.TemporaryDirectory(prefix='trova-release-296-') as folder:
            archive=Path(folder)/'payload.tgz'
            with tarfile.open(archive,'w:gz',format=tarfile.USTAR_FORMAT) as stream:
                for name,data in contents.items():
                    header=tarfile.TarInfo(name);header.size=len(data);header.mode=0o644
                    stream.addfile(header,io.BytesIO(data))
            subprocess.run(['scp','-q','-i',args.ssh_key,'-o','BatchMode=yes',str(archive),args.host+':'+cfg['archive']],check=True,timeout=120)
        bench.result['inFlightBeforeSwap']=bench.idle();bench.save()
        print('Replacing backed-up JAR and Python together',flush=True)
        swap_attempted=True
        bench.result['swap']=bench.remote(swap,cfg,timeout=300);bench.save()
        bench.result['healthSeconds']=bench.healthy()
        after=bench.state()
        assert after['service']=='active' and after['queueType']=='kafka' and not after['dropInPaths']
        assert after['jarSha256']==new['app.jar']
        assert after['pythonHashes']['extract_places.py']==new['pipeline-test/extract_places.py']
        assert all(after['pythonHashes'][n.split('/')[-1]]==sha for n,sha in unchanged.items())
        assert after['diagnosticFlags']==before['diagnosticFlags']
        assert after['autoDraftEnabled']==before['autoDraftEnabled'] and after['quotaSharesPerDay']==before['quotaSharesPerDay']
        import urllib.request,urllib.error
        for path in ['/api/places','/api/shares']:
            request=urllib.request.Request(args.base_url+path,data=b'{}' if path.endswith('shares') else None,headers={'Content-Type':'application/json'})
            try:urllib.request.urlopen(request,timeout=15);raise AssertionError('Unauthenticated API unexpectedly accepted')
            except urllib.error.HTTPError as error:
                assert error.code==401 and not error.headers.get_all('Set-Cookie')
        bench.result.update(deployed=True,after=after,unauthenticated401NoCookie=True,finalInFlight=bench.idle())
    except BaseException as error:
        bench.result['errors'].append({'stage':'deployment','type':type(error).__name__})
        if swap_attempted:
            probe=bench.remote(prefix+"\nbackup=pathlib.Path(cfg['backup'])\nprint(json.dumps({'hashes':hashes(cfg['old']),'backupComplete':all((backup/n).is_file() and hashlib.sha256((backup/n).read_bytes()).hexdigest()==sha for n,sha in cfg['old'].items()),'service':subprocess.run(['systemctl','is-active','trova-backend'],capture_output=True,text=True).stdout.strip()}))\n",cfg)
            bench.result['failureProbe']=probe
            if probe['hashes']!=old or probe['service']!='active':
                assert probe['backupComplete'],'Manual recovery needed: original pair incomplete'
                bench.result['rollbackResult']=bench.remote(rollback,cfg,timeout=300)
                bench.result.update(rollback=True,deployed=False,rollbackHealthSeconds=bench.healthy())
        raise
    finally:
        bench.result['finishedAt']=datetime.datetime.now().astimezone().isoformat();bench.save()
    print('Deployment verified; original pair preserved at',backup,flush=True)


if __name__=='__main__':
    main()
