#!/usr/bin/env python3
"""One local scheduled run. Manifest/receipt are private; no service configuration changes."""
import argparse
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
from types import SimpleNamespace


def check_window(manifest,now):
    if now<datetime.datetime.fromisoformat(manifest['earliestStart']):return 'too_early'
    if now>datetime.datetime.fromisoformat(manifest['latestStart']):return 'expired'
    return 'ready'


def verify_sources(manifest):
    for name,expected in manifest['sourceHashes'].items():
        if hashlib.sha256(Path(name).read_bytes()).hexdigest()!=expected:raise ValueError('reviewed_source_changed')
    actual=subprocess.check_output(['git','rev-parse','HEAD'],cwd=manifest['repository'],text=True).strip()
    if actual!=manifest['sourceCommit']:raise ValueError('checkout_commit_changed')
    if hashlib.sha256(Path(manifest['benchArguments']['env_file']).read_bytes()).hexdigest()!=manifest['privateEnvSha256']:
        raise ValueError('private_environment_changed')


def save_receipt(path,data):
    path=Path(path);temporary=path.with_suffix('.partial')
    fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
    with os.fdopen(fd,'w') as stream:
        json.dump(data,stream,ensure_ascii=False,indent=2);stream.write('\n');stream.flush();os.fsync(stream.fileno())
    temporary.replace(path)


def remove_owned_schedule(manifest,receipt_path,receipt):
    path=Path(manifest['plistPath'])
    if path.exists():
        if hashlib.sha256(path.read_bytes()).hexdigest()!=manifest['plistSha256']:
            receipt['scheduleCleanup']='unowned_plist_preserved';save_receipt(receipt_path,receipt);return
        path.unlink()
    receipt['scheduleCleanup']='owned_registration_removal_requested';save_receipt(receipt_path,receipt)
    # Receipt/results are already durable. Removing this launchd job may terminate this process.
    signal.signal(signal.SIGTERM,signal.SIG_DFL)
    subprocess.Popen(['/bin/launchctl','bootout','gui/'+str(os.getuid())+'/'+manifest['label']],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
    return 'owned_registration_removal_requested'


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--manifest',type=Path,required=True)
    args=parser.parse_args();manifest=json.loads(args.manifest.read_text())
    receipt_path=Path(manifest['receipt']);lock_path=receipt_path.with_suffix('.lock')
    lock_fd=os.open(lock_path,os.O_WRONLY|os.O_CREAT,0o600)
    with os.fdopen(lock_fd,'w') as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:return
        if receipt_path.exists():return
        now=datetime.datetime.now(datetime.timezone.utc)
        window=check_window(manifest,now)
        if window=='too_early':return
        receipt={'startedAt':now.isoformat(),'status':'starting','manifestSha256':hashlib.sha256(args.manifest.read_bytes()).hexdigest(),'output':manifest['benchArguments']['output'],'runtimeConfigurationChanged':False}
        save_receipt(receipt_path,receipt)
        try:
            if window!='ready':raise ValueError('start_window_expired')
            verify_sources(manifest)
            sys.path.insert(0,str(Path(__file__).parent))
            from repeat_production import RepeatBench
            from analyze_repeat import write_outputs
            signal.signal(signal.SIGTERM,lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
            receipt['status']='measuring';save_receipt(receipt_path,receipt)
            RepeatBench(SimpleNamespace(**manifest['benchArguments'])).run()
            summary=write_outputs(manifest['benchArguments']['output'],manifest['analysisDirectory'])
            receipt.update(status='measured',summary=summary);save_receipt(receipt_path,receipt)
            subprocess.run([manifest['exportPython'],str(Path(__file__).with_name('analyze_repeat.py')),
                            '--raw',manifest['benchArguments']['output'],'--output-dir',manifest['analysisDirectory'],
                            '--pdf',manifest['pdfOutput'],'--font',manifest['font']],check=True,timeout=90)
            receipt.update(status='complete',pdfOutput=manifest['pdfOutput'])
        except BaseException as error:
            receipt['status']='failed';receipt['errorType']=type(error).__name__
            raw_path=Path(manifest['benchArguments']['output'])
            if raw_path.exists():
                try:
                    raw=json.loads(raw_path.read_text());receipt['measurementComplete']=bool(raw.get('complete') and not raw.get('errors'))
                    receipt['measurementErrors']=raw.get('errors',[])
                    from analyze_repeat import write_outputs
                    write_outputs(raw_path,manifest['analysisDirectory'])
                except Exception:receipt['partialSummaryAvailable']=False
            print('Scheduled repeat stopped:',type(error).__name__,flush=True)
        finally:
            receipt['finishedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
            save_receipt(receipt_path,receipt)
            remove_owned_schedule(manifest,receipt_path,receipt)


if __name__=='__main__':main()
