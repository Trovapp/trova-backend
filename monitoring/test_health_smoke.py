"""Real k6, loopback HTTP fixtures only. Never connects to production."""
import http.server
import json
import os
from pathlib import Path
import subprocess
import threading
import time
import unittest

SCRIPT = Path(__file__).with_name('health-smoke.js')


class HealthSmokeTest(unittest.TestCase):
    def run_case(self, status=200, body=b'{"status":"UP"}', delay=0, overrides=None, cli=None):
        observed = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                observed.append(self.path)
                time.sleep(delay)
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                if status == 302:
                    self.send_header('Location', '/must-not-follow')
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
        env.update(TARGET='local', FIXTURE_PORT=str(server.server_port), REQUESTS='3', MODE='health')
        env.pop('READ_TOKEN', None)
        env.pop('RUN_PRODUCTION', None)
        env.update(overrides or {})
        try:
            result = subprocess.run(['k6', 'run', '--quiet', *(cli or []), str(SCRIPT)],
                                    env=env, capture_output=True, text=True, timeout=20)
            return result, observed
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_success_is_bounded(self):
        result, paths = self.run_case()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(paths, ['/actuator/health'] * 3)

    def test_failures_stop_after_one_request(self):
        for status, body in [(500, b'{}'), (200, b'{"status":"DOWN"}'), (200, b'bad json'), (302, b'{}')]:
            with self.subTest(status=status, body=body):
                result, paths = self.run_case(status, body)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(paths, ['/actuator/health'])

    def test_slow_response_stops(self):
        result, paths = self.run_case(delay=2.1)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(paths, ['/actuator/health'])

    def test_excessive_request_count_rejected_before_network(self):
        result, paths = self.run_case(overrides={'REQUESTS': '31'})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(paths, [])

    def test_cli_workload_override_rejected(self):
        result, paths = self.run_case(cli=['--vus', '2', '--iterations', '5'])
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(paths, [])

    def test_missing_target_rejected(self):
        result, paths = self.run_case(overrides={'TARGET': ''})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(paths, [])

    def test_places_uses_only_read_endpoint(self):
        result, paths = self.run_case(body=b'[{"id":1,"placeName":"fixture"}]',
                                      overrides={'MODE': 'places', 'READ_TOKEN': 'fixture-token'})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(paths, ['/api/places'] * 3)
        self.assertNotIn('fixture-token', result.stdout + result.stderr)

    def test_places_rejects_unexpected_body(self):
        result, paths = self.run_case(body=b'{"status":"UP"}',
                                      overrides={'MODE': 'places', 'READ_TOKEN': 'fixture-token'})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(paths, ['/api/places'])

    def test_places_without_token_makes_no_request(self):
        result, paths = self.run_case(overrides={'MODE': 'places'})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(paths, [])


if __name__ == '__main__':
    unittest.main()
