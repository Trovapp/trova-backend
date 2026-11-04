"""Offline evidence-accounting checks. No services or external calls."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("rust_feasibility", Path(__file__).with_name("analyze_rust_pipeline_feasibility.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def fixture():
    return {"complete": True, "errors": [], "results": [{
        "jobId": 1, "warmup": False, "mode": "kafka", "observedPath": "kafka", "status": "DONE",
        "videoId": "fixture", "processingSeconds": 10, "queueWaitMs": 200,
        "createdToAnalysisDoneSeconds": 10.2,
        "apiLogRows": [
            {"provider": "gemini", "operation": "extract_places.collect", "success": True,
             "latencyMs": 3000, "attempts": 1, "waitedMs": 0},
            {"provider": "gemini", "operation": "extract_places.filter", "success": True,
             "latencyMs": 2000, "attempts": 1, "waitedMs": 0},
            {"provider": "kakao", "operation": "keyword_search", "latencyMs": 100_000}]}]}


class AccountingTests(unittest.TestCase):
    def test_병렬_카카오_호출합을_빼거나_나머지를_CPU로_표시하지_않는다(self):
        result = module.analyze(fixture())
        self.assertEqual(result["samples"][0]["outsideGeminiLogWindowsMs"], 5000)
        self.assertFalse(result["samples"][0]["outsideGeminiLogWindowsIsCpuTime"])
        self.assertIsNone(result["availability"]["rustAfterResults"])

    def test_워밍업은_제외하고_큐경로는_구분한다(self):
        data = fixture()
        warmup = copy.deepcopy(data["results"][0]); warmup.update(jobId=2, warmup=True)
        other = copy.deepcopy(data["results"][0]); other.update(jobId=3, mode="async", observedPath="async")
        data["results"] += [warmup, other]
        result = module.analyze(data)
        self.assertEqual(result["warmupsExcluded"], 1)
        self.assertEqual(result["byMode"]["async"]["n"], 1)
        self.assertEqual(result["byMode"]["kafka"]["n"], 1)

    def test_누락된_재시도_기록은_0으로_간주하지_않는다(self):
        data = fixture(); data["results"][0]["apiLogRows"][0].update(attempts=None, waitedMs=None)
        row = module.analyze(data)["samples"][0]
        self.assertEqual(row["unknownAttemptRows"], 1)
        self.assertEqual(row["unknownWaitedRows"], 1)
        self.assertEqual(row["knownAttemptSum"], 1)

    def test_호출시간합이_전체보다_크면_나머지를_0으로_보정하지_않는다(self):
        data = fixture(); data["results"][0]["apiLogRows"][0]["latencyMs"] = 9000
        with self.assertRaises(ValueError): module.analyze(data)

    def test_중복호출이나_알수없는_AI호출은_합산하지_않는다(self):
        for operation in ["extract_places.collect", "trip-plan.draft"]:
            data = fixture(); extra = copy.deepcopy(data["results"][0]["apiLogRows"][0]); extra["operation"] = operation
            data["results"][0]["apiLogRows"].append(extra)
            with self.assertRaises(ValueError): module.analyze(data)

    def test_불완전_측정이나_실패_호출은_거절한다(self):
        for field in ["complete", "success"]:
            data = fixture()
            if field == "complete": data["complete"] = False
            else: data["results"][0]["apiLogRows"][0]["success"] = False
            with self.assertRaises(ValueError): module.analyze(data)

    def test_큐시간과_분석시간을_중복해_세지_않는다(self):
        data = fixture(); data["results"][0]["processingSeconds"] = 10.2
        with self.assertRaises(ValueError): module.analyze(data)

    def test_숫자_누락이나_무한대는_거절한다(self):
        for value in [None, True, float("nan"), float("inf"), -1]:
            data = fixture(); data["results"][0]["processingSeconds"] = value
            with self.assertRaises(ValueError): module.analyze(data)

    def test_개별_비율의_중앙값을_계산한다(self):
        data = fixture()
        other = copy.deepcopy(data["results"][0]); other.update(jobId=2, processingSeconds=20, createdToAnalysisDoneSeconds=20.2)
        data["results"].append(other)
        self.assertEqual(module.analyze(data)["byMode"]["kafka"]["allGeminiCallSharePercent"]["median"], 37.5)


if __name__ == "__main__":
    unittest.main()
