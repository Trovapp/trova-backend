import contextlib
import io
import json
import unittest
from unittest.mock import patch
import provider

class ProviderTest(unittest.TestCase):
    def run_provider(self, side_effect=None, result=None):
        output = io.StringIO()
        with patch.dict(provider.os.environ), patch.object(provider.sys, 'argv', ['provider.py', 'URL:https://youtu.be/fixture']), \
             patch.object(provider.run_pipeline, 'run', side_effect=side_effect, return_value=result), \
             contextlib.redirect_stdout(output):
            provider.main()
        return json.loads(output.getvalue())

    def test_non_place_judgment_is_empty_prediction(self):
        self.assertEqual(self.run_provider(SystemExit(provider.NOT_PLACE_CONTENT_MARKER + ' 장소 소개 아님')), [])

    def test_execution_errors_are_not_empty_predictions(self):
        for message in ['Gemini API error 401', 'SOURCE_RATE_LIMITED', 'SOURCE_UNAVAILABLE', 'NO_MEDIA', 'NOT_PLACE_CONTENT_OTHER']:
            with self.subTest(message=message), self.assertRaises(SystemExit):
                self.run_provider(SystemExit(message))

    def test_normal_names_are_kept(self):
        self.assertEqual(self.run_provider(result={'places':[{'name':'가야 랜드'}]}), ['가야랜드'])

if __name__ == '__main__':
    unittest.main()
