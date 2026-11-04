"""Real k6, loopback HTTP fixtures only. Never connects to production."""
import http.server
import os
from pathlib import Path
import subprocess
import threading
import time
import unittest

SCRIPT = Path(__file__).with_name('places-load.js')
PLACES = b'[{"id":1,"placeName":"fixture"}]'


class PlacesLoadTest(unittest.TestCase):
    def run_case(self, status=200, body=PLACES, delay=0, overrides=None, cli=None):
        observed = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                observed.append((self.path, self.headers.get('Authorization')))
                time.sleep(delay)
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                try:
                    self.wfile.write(body)
                except BrokenPipeError:
                    pass

            def log_message(self, *args):
                pass

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        env = {k: v for k, v in os.environ.items() if not k.startswith('K6_')}
        env.update(TARGET='local', FIXTURE_PORT=str(server.server_port), FIXTURE_DURATION='3s',
                   READ_TOKEN='fixture-token')
        env.pop('RUN_PRODUCTION', None)
        env.update(overrides or {})
        env = {k: v for k, v in env.items() if v is not None}
        try:
            result = subprocess.run(['k6', 'run', '--quiet', *(cli or []), str(SCRIPT)],
                                    env=env, capture_output=True, text=True, timeout=40)
            return result, observed
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_정상이면_5VU가_장소목록만_2초간격으로_읽는다(self):
        result, observed = self.run_case()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(observed)
        self.assertEqual({p for p, _ in observed}, {'/api/places'})
        self.assertEqual({a for _, a in observed}, {'Bearer fixture-token'})
        # 3초 동안 5 VU, 요청 뒤 2초 대기 → VU당 최대 2회
        self.assertLessEqual(len(observed), 10)
        self.assertGreaterEqual(len(observed), 5)
        self.assertNotIn('fixture-token', result.stdout + result.stderr)

    def test_401이면_서버오류와_구분해_AUTH_FAILED로_중단한다(self):
        result, observed = self.run_case(status=401, body=b'{}')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('AUTH_FAILED', result.stdout + result.stderr)
        self.assertLessEqual(len(observed), 5)

    def test_500이면_SERVER_ERROR로_중단한다(self):
        result, _ = self.run_case(status=500, body=b'{}')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('SERVER_ERROR', result.stdout + result.stderr)

    def test_응답하나가_2초를_넘으면_중단한다(self):
        result, _ = self.run_case(delay=2.1)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('SLOW_RESPONSE', result.stdout + result.stderr)

    def test_CLI로_VU를_바꾸면_요청없이_거부한다(self):
        result, observed = self.run_case(cli=['--vus', '20', '--duration', '3s'])
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(observed, [])

    def test_로컬에서도_시간을_3분보다_길게_줄수없다(self):
        result, observed = self.run_case(overrides={'FIXTURE_DURATION': '10m'})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(observed, [])

    def test_토큰이_없으면_요청하지_않는다(self):
        result, observed = self.run_case(overrides={'READ_TOKEN': None})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(observed, [])

    def test_대상이_없으면_요청하지_않는다(self):
        result, observed = self.run_case(overrides={'TARGET': ''})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(observed, [])


if __name__ == '__main__':
    unittest.main()
