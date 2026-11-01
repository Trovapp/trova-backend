"""Offline control-flow, cleanup and report checks. No real API/SSH/DB."""
import datetime
import hashlib
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock,Mock,patch
from production_queue_bench import Bench,SCRIPT_NAMES
from repeat_production import RepeatBench
from analyze_repeat import summarize
from run_scheduled_repeat import check_window,verify_sources,remove_owned_schedule
import run_scheduled_repeat


class RepeatControlTest(unittest.TestCase):
    def fixture(self):
        b=RepeatBench.__new__(RepeatBench)
        b.args=SimpleNamespace(expected_jar_sha256='jar',submission_deadline='2100-01-01T00:00:00+09:00')
        root=Path(__file__).resolve().parents[3]
        state={'pid':1,'service':'active','queueType':'kafka','dropInPaths':'','jarSha256':'jar','autoDraftEnabled':'true','quotaSharesPerDay':20,
               'diagnosticFlags':{},'pythonHashes':{n:hashlib.sha256((root/'pipeline-test'/n).read_bytes()).hexdigest() for n in SCRIPT_NAMES}}
        b.result={'results':[],'errors':[],'phase':'preflight','cleanups':[]}
        b.state=Mock(return_value=state);b.share_count=Mock(return_value=0);b.active_counts=Mock(return_value=[0,0,0])
        b.verify_runtime=Mock(return_value=state);b.healthy=Mock();b.save=Mock();b.capture=Mock();b.cleanup_row=Mock(side_effect=lambda row:row.update(cleanupComplete=True))
        b.budget=Mock(return_value={'guardEstimateIncludingLocal':0});b.query=Mock(return_value=[(20,)])
        return b

    def measure(self,b,*args,**kwargs):
        b.result['results'].append({'jobId':100+len(b.result['results']),'warmup':kwargs['warmup'],'status':'DONE'})

    def test_한도회복전에는_공유나_서버변경없이_중단한다(self):
        b=self.fixture();b.share_count.return_value=19
        with patch.object(Bench,'measure') as measure,patch.object(Bench,'switch_async') as switch,patch.object(Bench,'restore') as restore:
            with self.assertRaisesRegex(AssertionError,'quota_not_ready'):b.run()
        measure.assert_not_called();switch.assert_not_called();restore.assert_not_called()

    def test_준비한번과_측정여섯번을_남기고_각각_결과만_정리한다(self):
        b=self.fixture()
        with patch.object(Bench,'measure',autospec=True,side_effect=self.measure) as measure,patch.object(Bench,'switch_async') as switch,patch.object(Bench,'restore') as restore:b.run()
        self.assertEqual(measure.call_count,7);self.assertEqual(b.capture.call_count,7);self.assertEqual(b.cleanup_row.call_count,7)
        self.assertEqual([r['warmup'] for r in b.result['results']],[True]+[False]*6)
        self.assertEqual([r['jobId'] for r in b.result['results']],list(range(100,107)))
        self.assertTrue(b.result['complete']);switch.assert_not_called();restore.assert_not_called()

    def test_접수뒤_실패한_작업도_정리하고_추가접수를_멈춘다(self):
        b=self.fixture()
        def failing(b,*args,**kwargs):
            self.measure(b,*args,**kwargs)
            if len(b.result['results'])==2:raise RuntimeError('fixture failure')
        with patch.object(Bench,'measure',autospec=True,side_effect=failing) as measure:
            with self.assertRaises(RuntimeError):b.run()
        self.assertEqual(measure.call_count,2);self.assertEqual(b.cleanup_row.call_count,2)
        self.assertEqual(len(b.result['results']),2);self.assertNotIn('complete',b.result)

    def test_겹친_작업을_발견하면_결과를_보존하고_멈춘다(self):
        b=self.fixture();b.capture.side_effect=AssertionError('other_work_overlapped')
        with patch.object(Bench,'measure',autospec=True,side_effect=self.measure) as measure:
            with self.assertRaisesRegex(AssertionError,'other_work_overlapped'):b.run()
        self.assertEqual(measure.call_count,1);b.cleanup_row.assert_called_once()

    def test_예산가드나_기한이_끝나면_새공유를_넣지_않는다(self):
        for condition in ['budget','deadline','busy']:
            b=self.fixture()
            if condition=='budget':b.budget.return_value={'guardEstimateIncludingLocal':60}
            if condition=='deadline':b.args.submission_deadline='2000-01-01T00:00:00+09:00'
            if condition=='busy':b.active_counts.return_value=[1,0,0]
            with self.subTest(condition=condition),patch.object(Bench,'measure') as measure:
                with self.assertRaises(AssertionError):b.run()
                measure.assert_not_called()

    def test_운영해시가_바뀌면_접수하지_않는다(self):
        b=self.fixture();b.args.expected_jar_sha256='different'
        with patch.object(Bench,'measure') as measure:
            with self.assertRaisesRegex(AssertionError,'unexpected_jar'):b.run()
        measure.assert_not_called()

    def test_같은버전이라도_서버가_재시작됐으면_멈춘다(self):
        b=self.fixture();before=dict(b.state.return_value);b.result['before']=before
        b.state.return_value=dict(before,pid=2)
        with self.assertRaisesRegex(AssertionError,'server_restarted'):RepeatBench.verify_runtime(b)


class CleanupTest(unittest.TestCase):
    def fixture(self,directory):
        b=RepeatBench.__new__(RepeatBench);b.args=SimpleNamespace(backup_dir=directory);b.marker='owned'
        b.result={'cleanups':[]};b.idle=Mock();b.save=Mock();b.query=Mock(return_value=[(0,0)])
        db=MagicMock();b.db=MagicMock();b.db.return_value.__enter__.return_value=db
        drafts=[({'id':9,'job_ids':'42'},)];places=[({'id':10,'processing_job_id':42},)]
        db.execute.side_effect=[Mock(fetchall=Mock(return_value=drafts)),Mock(fetchall=Mock(return_value=places)),Mock(fetchone=Mock(return_value=(0,))),SimpleNamespace(rowcount=1),SimpleNamespace(rowcount=1)]
        return b,db

    def test_백업에_실패하면_장소나_초안을_삭제하지_않는다(self):
        with tempfile.TemporaryDirectory() as folder:
            b,db=self.fixture(folder)
            with patch('repeat_production.os.open',side_effect=OSError('fixture backup failure')):
                with self.assertRaises(OSError):b.cleanup_row({'jobId':42})
            self.assertEqual(db.execute.call_count,3)
            self.assertFalse(any('delete ' in c.args[0].lower() for c in db.execute.call_args_list))

    def test_해당_사용자와_작업의_결과만_백업하고_삭제한다(self):
        with tempfile.TemporaryDirectory() as folder:
            b,db=self.fixture(folder);row={'jobId':42};b.cleanup_row(row)
            backup=Path(folder)/'owned-job-42.json';self.assertEqual(backup.stat().st_mode&0o777,0o600)
            self.assertEqual(json.loads(backup.read_text())['places'][0]['processing_job_id'],42)
            for call in db.execute.call_args_list[3:]:
                self.assertIn('user_id=1',call.args[0]);self.assertIn(call.args[1],[(42,),('42',)])
            self.assertTrue(row['cleanupComplete']);self.assertTrue(b.result['cleanups'][0]['jobAndApiLogsKept'])

    def test_여행에_추가된_테스트장소는_자동삭제하지_않는다(self):
        with tempfile.TemporaryDirectory() as folder:
            b,db=self.fixture(folder)
            db.execute.side_effect=[Mock(fetchall=Mock(return_value=[])),Mock(fetchall=Mock(return_value=[])),Mock(fetchone=Mock(return_value=(1,)))]
            with self.assertRaisesRegex(AssertionError,'test_places_now_in_trip_preserved'):b.cleanup_row({'jobId':42})
            self.assertFalse(any('delete ' in c.args[0].lower() for c in db.execute.call_args_list))

    def test_사용자가_승인한_테스트초안은_자동삭제하지_않는다(self):
        with tempfile.TemporaryDirectory() as folder:
            b,db=self.fixture(folder)
            db.execute.side_effect=[Mock(fetchall=Mock(return_value=[({'status':'APPROVED'},)])),Mock(fetchall=Mock(return_value=[]))]
            with self.assertRaisesRegex(AssertionError,'approved_test_draft_preserved'):b.cleanup_row({'jobId':42})
            self.assertFalse(any('delete ' in c.args[0].lower() for c in db.execute.call_args_list))


class SummaryAndScheduleTest(unittest.TestCase):
    def row(self,warmup=False,seconds=10):
        return {'jobId':1,'warmup':warmup,'status':'DONE','queueWaitMs':200,'createdToAnalysisDoneSeconds':seconds,
                'drafts':[{'status':'READY','analysisDoneToDraftCreatedSeconds':1,'draftCreatedToLastUpdateSeconds':3,'shareCreatedToDraftLastUpdateSeconds':14}],
                'javaAiWindowLogs':[{'latencyMs':2000}]}

    def test_준비와_실패를_숨기지_않고_성공표본의_통계를_별도로_낸다(self):
        raw={'complete':False,'errors':[{'type':'fixture'}],'results':[self.row(True,100),self.row(False,10),self.row(False,12),{'jobId':4,'warmup':False,'status':'FAILED'}]}
        s=summarize(raw)
        self.assertEqual(s['preparationRows'],1);self.assertEqual(s['attemptedMeasured'],3);self.assertEqual(s['failedOrIncompleteMeasured'],1)
        self.assertEqual(s['metrics']['analysisSeconds']['median'],11);self.assertFalse(s['complete']);self.assertFalse(s['speedImprovementProven'])

    def test_허용시간밖에서는_실행하지_않는다(self):
        manifest={'earliestStart':'2026-11-02T00:10:00+09:00','latestStart':'2026-11-02T00:25:00+09:00'}
        for time,expected in [('00:09:00','too_early'),('00:10:00','ready'),('00:26:00','expired')]:
            self.assertEqual(check_window(manifest,datetime.datetime.fromisoformat('2026-11-02T'+time+'+09:00')),expected)

    def test_검토후_코드가_바뀌면_환경을_읽기전에_멈춘다(self):
        with tempfile.TemporaryDirectory() as folder:
            code=Path(folder)/'tool.py';code.write_text('changed')
            with patch('run_scheduled_repeat.subprocess.check_output') as command:
                with self.assertRaisesRegex(ValueError,'reviewed_source_changed'):
                    verify_sources({'sourceHashes':{str(code):'0'*64}})
            command.assert_not_called()


class ScheduledReceiptTest(unittest.TestCase):
    def test_이미_접수영수증이_있으면_중복실행하지_않는다(self):
        with tempfile.TemporaryDirectory() as folder:
            folder=Path(folder);receipt=folder/'receipt.json';receipt.write_text('{"status":"running"}')
            manifest=folder/'manifest.json';manifest.write_text(json.dumps({'receipt':str(receipt)}))
            with patch('sys.argv',['tool','--manifest',str(manifest)]),patch.object(run_scheduled_repeat,'verify_sources') as verify:
                run_scheduled_repeat.main()
            verify.assert_not_called();self.assertEqual(json.loads(receipt.read_text())['status'],'running')

    def test_기한이_지난_예약은_운영도구를_실행하지_않는다(self):
        with tempfile.TemporaryDirectory() as folder:
            folder=Path(folder);receipt=folder/'receipt.json';manifest=folder/'manifest.json'
            manifest.write_text(json.dumps({'receipt':str(receipt),'earliestStart':'2000-01-01T00:00:00+09:00','latestStart':'2000-01-01T00:25:00+09:00','benchArguments':{'output':str(folder/'raw.json')}}))
            with patch('sys.argv',['tool','--manifest',str(manifest)]),patch.object(run_scheduled_repeat,'verify_sources') as verify,patch.object(run_scheduled_repeat,'remove_owned_schedule') as cleanup:
                run_scheduled_repeat.main()
            verify.assert_not_called();cleanup.assert_called_once();self.assertEqual(json.loads(receipt.read_text())['status'],'failed')

    def test_타인의_예약내용은_지우거나_등록해제하지_않는다(self):
        with tempfile.TemporaryDirectory() as folder:
            folder=Path(folder);plist=folder/'other.plist';plist.write_text('other contents');receipt=folder/'receipt.json';data={}
            with patch('run_scheduled_repeat.subprocess.Popen') as command:
                remove_owned_schedule({'plistPath':str(plist),'plistSha256':'0'*64},receipt,data)
            self.assertTrue(plist.exists());command.assert_not_called()
            self.assertEqual(json.loads(receipt.read_text())['scheduleCleanup'],'unowned_plist_preserved')


if __name__=='__main__':unittest.main()
