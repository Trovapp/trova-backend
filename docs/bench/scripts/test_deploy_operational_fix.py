"""Execute the actual remote payload against owned temp files and fake systemctl.
No SSH, service restart, credentials or real deployment.
"""
import ast
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


class PairedDeploymentTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='trova-deploy-test-')
        self.addCleanup(self.temp.cleanup)
        self.folder=Path(self.temp.name)
        self.root=self.folder/'app';self.root.mkdir()
        self.old={'app.jar':b'old jar','pipeline-test/extract_places.py':b'old python'}
        self.new={'app.jar':b'new jar','pipeline-test/extract_places.py':b'new python'}
        self.unchanged={'pipeline-test/run_pipeline.py':b'keep this'}
        for name,data in (self.old|self.unchanged).items():
            p=self.root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
        digest=lambda data:{name:hashlib.sha256(v).hexdigest() for name,v in data.items()}
        self.cfg={'old':digest(self.old),'new':digest(self.new),'unchanged':digest(self.unchanged),
                  'backup':str(self.folder/'backup'),'archive':str(self.folder/'payload.tgz')}
        with tarfile.open(self.cfg['archive'],'w:gz') as archive:
            for name,data in self.new.items():
                header=tarfile.TarInfo(name);header.size=len(data);archive.addfile(header,io.BytesIO(data))
        tree=ast.parse(Path(__file__).with_name('deploy_operational_fix.py').read_text())
        main=next(node for node in tree.body if isinstance(node,ast.FunctionDef) and node.name=='main')
        assignments={node.targets[0].id:node.value for node in main.body if isinstance(node,ast.Assign) and isinstance(node.targets[0],ast.Name)}
        self.prefix=ast.literal_eval(assignments['prefix']).replace("pathlib.Path('/opt/trova-backend')","pathlib.Path("+repr(str(self.root))+")")
        self.swap=self.prefix+ast.literal_eval(assignments['swap'].right)
        self.rollback=self.prefix+ast.literal_eval(assignments['rollback'].right)
        self.service=[]
        self.owner=SimpleNamespace(pw_uid=os.getuid(),pw_gid=os.getgid())

    def run_code(self,code,copy_effect=None):
        def service(args,**kwargs):self.service.append(args);return SimpleNamespace(returncode=0)
        with patch('subprocess.run',side_effect=service),patch('pwd.getpwnam',return_value=self.owner),patch('os.chown'),contextlib.redirect_stdout(io.StringIO()):
            if copy_effect:
                with patch('shutil.copyfile',side_effect=copy_effect):exec(code,{'cfg':self.cfg})
            else:exec(code,{'cfg':self.cfg})

    def check_files(self,expected):
        for name,data in (expected|self.unchanged).items():self.assertEqual((self.root/name).read_bytes(),data)

    def test_success_backs_up_and_replaces_both_files(self):
        self.run_code(self.swap);self.check_files(self.new)
        for name,data in self.old.items():self.assertEqual((Path(self.cfg['backup'])/name).read_bytes(),data)
        self.assertFalse(Path(self.cfg['archive']).exists())
        self.assertEqual([r[1] for r in self.service],['stop','start'])

    def test_second_copy_failure_restores_the_original_pair(self):
        real_copy=shutil.copyfile;failed=False
        def failing(source,target,*args,**kwargs):
            nonlocal failed
            if not failed and 'trova-release-296-' in str(source) and str(source).endswith('extract_places.py'):
                failed=True;raise OSError('injected copy failure')
            return real_copy(source,target,*args,**kwargs)
        with self.assertRaises(OSError):self.run_code(self.swap,failing)
        self.assertTrue(failed);self.check_files(self.old)
        self.assertFalse(list(self.root.rglob('*.release-296')))
        self.assertEqual([r[1] for r in self.service],['stop','restart'])

    def test_baseline_mismatch_does_not_stop_service(self):
        (self.root/'app.jar').write_bytes(b'unexpected')
        with self.assertRaises(AssertionError):self.run_code(self.swap)
        self.assertFalse(self.service)
        self.assertEqual((self.root/'pipeline-test/extract_places.py').read_bytes(),self.old['pipeline-test/extract_places.py'])

    def test_corrupt_payload_does_not_replace_runtime(self):
        self.cfg['new']['app.jar']='0'*64
        with self.assertRaises(AssertionError):self.run_code(self.swap)
        self.check_files(self.old);self.assertFalse(self.service)

    def test_explicit_rollback_restores_both_files(self):
        self.run_code(self.swap);self.service.clear()
        self.run_code(self.rollback);self.check_files(self.old)
        self.assertEqual([r[1] for r in self.service],['stop','start'])

    def test_incomplete_backup_refuses_rollback(self):
        self.run_code(self.swap);self.service.clear()
        (Path(self.cfg['backup'])/'pipeline-test/extract_places.py').unlink()
        with self.assertRaises(FileNotFoundError):self.run_code(self.rollback)
        self.check_files(self.new);self.assertFalse(self.service)


if __name__=='__main__':unittest.main()
