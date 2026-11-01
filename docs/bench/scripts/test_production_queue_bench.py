"""Offline lifecycle checks; never connects to a DB, SSH host, or real service."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('production_bench', Path(__file__).with_name('production_queue_bench.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class LifecycleTest(unittest.TestCase):
    def fixture(self, directory):
        bench = module.Bench.__new__(module.Bench)
        bench.drop = str(Path(directory)/'owned.conf')
        bench.override = str(Path(directory)/'owned.env')
        bench.conf_text = '[Service]\n# test-owner\nEnvironmentFile=' + bench.override + '\n'
        bench.override_attempted = True
        bench.args = types.SimpleNamespace(expected_jar_sha256='unchanged')
        state = {'service':'active','queueType':'kafka','jarSha256':'unchanged','dropInPaths':'',
                 'pythonHashes':{},'diagnosticFlags':{}}
        bench.result = {'before':state,'transitions':[],'errors':[]}
        bench.state = Mock(return_value=state)
        bench.idle = Mock(return_value=[0,0,0])
        bench.healthy = Mock(return_value=0)
        bench.save = Mock()
        def offline_remote(code, config, **kwargs):
            namespace = {'cfg':config}
            stdout = io.StringIO()
            # Real temporary files, all service commands intercepted locally.
            with contextlib.redirect_stdout(stdout):
                exec(compile(code,'remote-fixture','exec'),namespace)
            return json.loads(stdout.getvalue())
        bench.remote = offline_remote
        return bench

    def test_소유한_설정을_삭제한뒤_재시작하고_원래_상태를_확인한다(self):
        with tempfile.TemporaryDirectory() as directory:
            bench = self.fixture(directory)
            Path(bench.drop).write_text(bench.conf_text)
            Path(bench.override).write_text('APP_QUEUE_TYPE=async\n')
            with patch.object(subprocess,'run') as command:
                bench.restore()
            self.assertFalse(Path(bench.drop).exists())
            self.assertFalse(Path(bench.override).exists())
            self.assertEqual([call.args[0] for call in command.call_args_list],
                             [['systemctl','daemon-reload'],['systemctl','restart','trova-backend']])
            self.assertTrue(bench.result['restored'])
            self.assertFalse(bench.override_attempted)

    def test_소유하지_않은_내용은_보존하고_서비스를_변경하지_않는다(self):
        with tempfile.TemporaryDirectory() as directory:
            bench = self.fixture(directory)
            Path(bench.drop).write_text('someone else config')
            Path(bench.override).write_text('APP_QUEUE_TYPE=async\n')
            with patch.object(subprocess,'run') as command:
                with self.assertRaises(AssertionError):
                    bench.restore()
            self.assertEqual(Path(bench.drop).read_text(),'someone else config')
            self.assertTrue(Path(bench.override).exists())
            command.assert_not_called()
            self.assertTrue(bench.override_attempted)

    def test_이미_복구된_서비스는_다시_재시작하지_않는다(self):
        with tempfile.TemporaryDirectory() as directory:
            bench = self.fixture(directory)
            with patch.object(subprocess,'run') as command:
                bench.restore()
            command.assert_not_called()
            self.assertTrue(bench.result['restored'])

    def test_전환이_실패해도_복구할_소유_설정을_기억한다(self):
        with tempfile.TemporaryDirectory() as directory:
            bench = self.fixture(directory)
            bench.override_attempted = False
            with patch.object(subprocess,'run',side_effect=RuntimeError('simulated restart failure')):
                with self.assertRaises(RuntimeError):
                    bench.switch_async()
            self.assertTrue(bench.override_attempted)
            self.assertEqual(Path(bench.drop).read_text(),bench.conf_text)
            self.assertEqual(Path(bench.override).read_text(),'APP_QUEUE_TYPE=async\n')
            self.assertEqual(Path(bench.override).stat().st_mode & 0o777,0o600)
            with patch.object(subprocess,'run'):
                bench.restore()
            self.assertTrue(bench.result['restored'])

    def test_측정과_복구가_실패해도_데이터_정리를_시도한다(self):
        with tempfile.TemporaryDirectory() as directory:
            bench = self.fixture(directory)
            repo = Path(module.__file__).resolve().parents[3]
            state = bench.result['before']
            state.update(quotaSharesPerDay=20,pythonHashes={name:module.hashlib.sha256((repo/'pipeline-test'/name).read_bytes()).hexdigest() for name in module.SCRIPT_NAMES})
            bench.query = Mock(return_value=[(2,)])
            bench.measure = Mock(side_effect=ValueError('simulated measurement failure'))
            bench.restore = Mock(side_effect=RuntimeError('simulated restore failure'))
            bench.cleanup = Mock()
            with self.assertRaises(ValueError):
                bench.run()
            bench.restore.assert_called_once()
            bench.cleanup.assert_called_once()
            self.assertEqual([row['stage'] for row in bench.result['errors']],['measurement','restore'])
            self.assertFalse(bench.result['restored'])
            self.assertIn('finishedAt',bench.result)


if __name__ == '__main__':
    unittest.main()
