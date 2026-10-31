import contextlib
import io
import json
import os
import unittest
from unittest.mock import patch
import extract_places

class ExtractionTraceTest(unittest.TestCase):
    def test_opt_in_trace_does_not_change_stdout(self):
        err, out = io.StringIO(), io.StringIO()
        with patch.dict(os.environ, {'PIPELINE_TRACE': '1'}), contextlib.redirect_stderr(err), contextlib.redirect_stdout(out):
            extract_places.trace_selection(['테라로사', '부산'], [{'name': '테라로사 강릉점'}])
        row = json.loads(err.getvalue().split(':', 1)[1])
        self.assertEqual(out.getvalue(), '')
        self.assertEqual(row['unmatchedCandidates'], ['테라로사', '부산'])
        self.assertEqual(row['reasonAvailability'], 'not_returned_by_model')

    def test_disabled_by_default(self):
        err = io.StringIO()
        with patch.dict(os.environ, {'PIPELINE_TRACE': '0'}), contextlib.redirect_stderr(err):
            extract_places.trace_selection(['부산'], [])
        self.assertEqual(err.getvalue(), '')

    def parse_audit(self, data, candidate_count=1):
        err = io.StringIO()
        with patch.dict(os.environ, {'PIPELINE_TRACE': '1'}), contextlib.redirect_stderr(err):
            places = extract_places.parse_filter(json.dumps(data), candidate_count, True)
        trace = json.loads(err.getvalue().split(':', 1)[1])
        return places, trace

    def test_진단_누락에도_유효한_장소를_보존한다(self):
        places = [{'name': '가야랜드'}]
        for decisions in [None, [], 'invalid', [None]]:
            with self.subTest(decisions=decisions):
                got, trace = self.parse_audit({'places': places, 'decisions': decisions})
                self.assertEqual(got, places)
                self.assertEqual(trace['status'], 'incomplete')
                self.assertTrue(trace['validationErrors'])

    def test_진단이_기존_배열_형식을_반환해도_장소를_보존한다(self):
        got, trace = self.parse_audit([{'name': '가야랜드'}])
        self.assertEqual(got, [{'name': '가야랜드'}])
        self.assertEqual(trace['status'], 'incomplete')

    def test_포함_제외와_최종목록_연결을_검사한다(self):
        decision = dict(candidateIndex=0, action='include', placeIndices=[0], reason='목적지', evidence='화면 표기')
        data = {'places': [{'name': '가야랜드'}], 'decisions': [decision]}
        _, trace = self.parse_audit(data)
        self.assertEqual(trace['status'], 'complete')
        for action, targets, index in [('include', [], 0), ('exclude', [0], 0), ('include', [1], 0),
                                       ('include', [True], 0), ('include', [0, 0], 0),
                                       ('include', [0], True), ('unknown', [0], 0)]:
            with self.subTest(action=action, targets=targets, index=index):
                decision.update(action=action, placeIndices=targets, candidateIndex=index)
                _, trace = self.parse_audit(data)
                self.assertEqual(trace['status'], 'incomplete')

    def test_동일_장소의_다른_후보_표기를_허용한다(self):
        decisions = [dict(candidateIndex=i, action='include', placeIndices=[0], reason='같은 장소', evidence='') for i in range(2)]
        _, trace = self.parse_audit({'places': [{'name': '테라로사 강릉점'}], 'decisions': decisions}, 2)
        self.assertEqual(trace['status'], 'complete')
        decisions[1]['candidateIndex'] = 0
        _, trace = self.parse_audit({'places': [{'name': '테라로사 강릉점'}], 'decisions': decisions}, 2)
        self.assertEqual(trace['status'], 'incomplete')

    def test_정상적인_제외와_빈_결과를_허용한다(self):
        decision = dict(candidateIndex=0, action='exclude', placeIndices=[], reason='경유', evidence='역에서 출발')
        got, trace = self.parse_audit({'places': [], 'decisions': [decision]})
        self.assertEqual(got, [])
        self.assertEqual(trace['status'], 'complete')

    def test_진단_오류로_API를_다시_호출하지_않는다(self):
        text = json.dumps({'places': [{'name': '가야랜드'}], 'decisions': []})
        payload = {'candidates': [{'content': {'parts': [{'text': text}]}}]}
        with patch.object(extract_places, 'call_gemini', return_value=payload) as call:
            result = extract_places.call_gemini_with_repair([], 'model', 'test-key', 'filter',
                lambda value: extract_places.parse_filter(value, 1, True))
        self.assertEqual(result, [{'name': '가야랜드'}])
        self.assertEqual(call.call_count, 1)

    def test_장소_자체가_잘못됐으면_기존_재질의와_실패를_유지한다(self):
        for places in [None, [None], [{'name': ''}], [{'name': 1}]]:
            with self.subTest(places=places), self.assertRaises(ValueError):
                self.parse_audit({'places': places, 'decisions': []})
        payload = {'candidates': [{'content': {'parts': [{'text': '{"places":null}'}]}}]}
        with patch.object(extract_places, 'call_gemini', return_value=payload) as call:
            with self.assertRaises(SystemExit):
                extract_places.call_gemini_with_repair([], 'model', 'test-key', 'filter',
                    lambda value: extract_places.parse_filter(value, 1, True))
        self.assertEqual(call.call_count, 2)

    def test_default_format_is_preserved(self):
        self.assertEqual(extract_places.parse_filter('[{"name":"가야랜드"}]', 1, False), [{'name':'가야랜드'}])
        with self.assertRaises(ValueError):
            extract_places.parse_filter('[null]', 1, False)

    def test_audit_trace_marks_model_reported_reasons(self):
        err = io.StringIO()
        with patch.dict(os.environ, {'PIPELINE_TRACE': '1'}), contextlib.redirect_stderr(err):
            extract_places.trace_selection(['부산'], [], audit=True)
        row = json.loads(err.getvalue().split(':', 1)[1])
        self.assertEqual(row['reasonAvailability'], 'see_decisions_trace')
