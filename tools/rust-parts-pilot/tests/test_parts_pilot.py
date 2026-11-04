"""Rust parts 비교(#312) 도구 시험. 실행: python3 -m unittest discover -s tools/rust-parts-pilot/tests

Rust 바이너리가 필요한 시험은 먼저 `cargo build --release --locked` 를 해 두어야 하고, 없으면 건너뛴다.
"""
from __future__ import annotations

import base64
import contextlib
import io
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT.parents[1]
sys.path.insert(0, str(ROOT / "python"))
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(REPO / "pipeline-test"))

import extract_places  # noqa: E402
import parts_control  # noqa: E402
from common import RUST_BIN  # noqa: E402

HAS_RUST = RUST_BIN.exists()

AUDIT_RUNNER = r"""
import runpy, sys
def hook(event, args):
    if event in ("socket.connect", "socket.getaddrinfo", "urllib.Request", "http.client.connect"):
        raise SystemExit(f"NETWORK_USED {event}")
    if event == "open" and args and isinstance(args[0], (str, bytes)) and str(args[0]).endswith(".env"):
        raise SystemExit(f"ENV_FILE_OPENED {args[0]}")
sys.addaudithook(hook)
sys.argv = [sys.argv[1]] + sys.argv[2:]
runpy.run_path(sys.argv[0], run_name="__main__")
"""


class 기존함수재사용(unittest.TestCase):
    def test_제어_CLI는_build_parts_결과를_그대로_낸다(self):
        with tempfile.TemporaryDirectory() as d:
            audio = Path(d) / "a.mp3"
            audio.write_bytes(b"\x00\x01mp3")
            frame = Path(d) / "프레임.jpg"
            frame.write_bytes(b"\xff\xd8jpg")
            manifest = {"prompt": "P", "transcript": "자막", "audio_path": str(audio), "frame_paths": [str(frame)],
                        "candidates": ["해운대", "a\"b"], "post_description": "설명", "photo_post": True}
            got = parts_control.build_request(parts_control.parse_manifest(json.dumps(manifest).encode()))
            want = extract_places.build_parts("자막", audio, [frame], prompt="P", candidates=["해운대", "a\"b"],
                                              post_description="설명", photo_post=True)
            self.assertEqual(got, want)
            self.assertEqual(base64.b64decode(got[2]["inline_data"]["data"]), b"\x00\x01mp3")

    def test_build_parts_호출은_키와_네트워크_함수를_부르지_않는다(self):
        with mock.patch.object(extract_places, "load_api_key", side_effect=AssertionError("key")), \
                mock.patch.object(extract_places, "call_gemini", side_effect=AssertionError("gemini")), \
                mock.patch("urllib.request.urlopen", side_effect=AssertionError("network")):
            out, kind, _ = parts_control.handle(json.dumps({"prompt": "P", "transcript": "t"}).encode())
        self.assertIsNone(kind)
        self.assertEqual(out, '[{"text":"자막/전사 텍스트:\\nt"},{"text":"P"}]\n'.encode())

    def test_CLI_프로세스는_소켓을_열거나_env_파일을_읽지_않는다(self):
        # 감사 훅(audit hook)으로 소켓 연결·urllib 요청·.env 열기를 막은 상태에서 지속 모드를 돌린다.
        with tempfile.TemporaryDirectory() as d:
            audio = Path(d) / "a.mp3"
            audio.write_bytes(b"x" * 100)
            lines = [json.dumps({"prompt": "P", "audio_path": str(audio), "candidates": ["a"]}),
                     json.dumps({"prompt": "P", "video_uri": "https://www.youtube.com/watch?v=x"})]
            env = {k: v for k, v in os.environ.items() if k != "GEMINI_API_KEY"}
            r = subprocess.run([sys.executable, "-c", AUDIT_RUNNER, str(ROOT / "python" / "parts_control.py"), "--serve"],
                               input=("\n".join(lines) + "\n").encode(), capture_output=True, env=env, cwd=d, timeout=60)
        self.assertEqual(r.returncode, 0, r.stderr.decode())
        self.assertEqual(r.stdout.count(b"\n"), 2)
        self.assertNotIn(b"NETWORK_USED", r.stderr + r.stdout)
        self.assertNotIn(b"ENV_FILE_OPENED", r.stderr + r.stdout)

    def test_감사_훅은_실제_소켓_연결을_잡는다(self):
        # 위 시험의 대조군: 훅이 실제로 동작하는지 확인한다(연결 시도 전에 막히므로 네트워크는 쓰지 않는다).
        with tempfile.TemporaryDirectory() as d:
            script = Path(d) / "connect.py"
            script.write_text("import socket\nsocket.socket().connect(('127.0.0.1', 9))\n", encoding="utf-8")
            r = subprocess.run([sys.executable, "-c", AUDIT_RUNNER, str(script)], capture_output=True, timeout=60)
        self.assertIn(b"NETWORK_USED socket.connect", r.stderr)


class 출력과_실패(unittest.TestCase):
    def test_출력은_키_정렬_공백없음_UTF8이다(self):
        out = parts_control.encode_parts([{"text": "한글"}, {"inline_data": {"mime_type": "m", "data": "d"}}])
        self.assertEqual(out, '[{"text":"한글"},{"inline_data":{"data":"d","mime_type":"m"}}]\n'.encode())

    def test_한번_실행_모드는_실패하면_stdout이_비고_종료코드_1(self):
        with tempfile.TemporaryDirectory() as d:
            m = Path(d) / "m.json"
            m.write_text(json.dumps({"prompt": "P", "audio_path": str(Path(d) / "없음.mp3")}), encoding="utf-8")
            stdout, stderr = io.StringIO(), io.StringIO()
            buf = io.BytesIO()
            with mock.patch.object(sys, "stdout", mock.MagicMock(buffer=buf)), contextlib.redirect_stderr(stderr):
                code = parts_control.main([str(m)])
        self.assertEqual(code, 1)
        self.assertEqual(buf.getvalue(), b"")
        self.assertIn("not_found", stderr.getvalue())

    def test_지속_모드는_오류_뒤에도_다음_요청을_처리한다(self):
        payload = b"{bad\n" + json.dumps({"prompt": "P"}).encode() + b"\n"
        r = subprocess.run([sys.executable, str(ROOT / "python" / "parts_control.py"), "--serve"], input=payload,
                           capture_output=True, timeout=60)
        self.assertEqual(r.stdout, b'{"error":{"kind":"invalid_json"}}\n[{"text":"P"}]\n')

    def test_실험_한도를_넘는_입력은_input_too_large(self):
        with tempfile.TemporaryDirectory() as d:
            big = Path(d) / "big.bin"
            with big.open("wb") as f:
                f.truncate(parts_control.MAX_INPUT_BYTES + 1)
            _, kind, _ = parts_control.handle(json.dumps({"prompt": "P", "audio_path": str(big)}).encode())
        self.assertEqual(kind, "input_too_large")

    def test_의미_검사는_바뀐_미디어_바이트를_잡는다(self):
        import check_compat  # noqa: PLC0415

        import hashlib  # noqa: PLC0415
        parts = [{"inline_data": {"mime_type": "audio/mpeg", "data": base64.b64encode(b"abc").decode()}}, {"text": "P"}]
        good = [{"path": "a.mp3", "sha256": hashlib.sha256(b"abc").hexdigest()}]
        bad = [{"path": "a.mp3", "sha256": hashlib.sha256(b"abd").hexdigest()}]
        out = json.dumps(parts).encode()
        self.assertEqual(check_compat.semantic_check(out, good)["problems"], [])
        self.assertTrue(check_compat.semantic_check(out, bad)["problems"])
        self.assertTrue(check_compat.semantic_check(out, good + good)["problems"])


@unittest.skipUnless(HAS_RUST, "Rust release 바이너리 없음(cargo build --release --locked)")
class 두_구현_비교(unittest.TestCase):
    def _both(self, payload: bytes, cwd: str) -> tuple[bytes, bytes]:
        py = subprocess.run([sys.executable, str(ROOT / "python" / "parts_control.py"), "--serve"], input=payload,
                            capture_output=True, cwd=cwd, timeout=60)
        rs = subprocess.run([str(RUST_BIN), "--serve"], input=payload, capture_output=True, cwd=cwd, timeout=60)
        return py.stdout, rs.stdout

    def test_한글_경로와_큰_파일에서_바이트가_같다(self):
        with tempfile.TemporaryDirectory() as d:
            folder = Path(d) / "한글 폴더"
            folder.mkdir()
            (folder / "음성.mp3").write_bytes(os.urandom(3 * 1024 * 1024))
            (folder / "사진.webp").write_bytes(os.urandom(1000))
            req = {"prompt": "P", "audio_path": "한글 폴더/음성.mp3", "frame_paths": ["한글 폴더/사진.webp", "./한글 폴더//사진.webp"],
                   "candidates": ["😀", "\u0001"], "photo_post": True}
            py, rs = self._both(json.dumps(req, ensure_ascii=False).encode() + b"\n", d)
        self.assertTrue(py.startswith(b"[{"))
        self.assertEqual(py, rs)

    def test_취소하면_자식_프로세스가_끝난다(self):
        for cmd in ([sys.executable, str(ROOT / "python" / "parts_control.py"), "--serve"], [str(RUST_BIN), "--serve"]):
            with self.subTest(cmd=cmd[-2]):
                p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                time.sleep(0.2)
                p.send_signal(signal.SIGTERM)
                self.assertIsNotNone(p.wait(timeout=10))
                p.stdin.close(); p.stdout.close(); p.stderr.close()
                q = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                q.stdin.close()  # stdin 이 닫히면 정상 종료
                self.assertEqual(q.wait(timeout=10), 0)
                q.stdout.close(); q.stderr.close()

    def test_측정기의_자식_정리는_살아있는_프로세스를_죽인다(self):
        import bench  # noqa: PLC0415

        child = bench.Child([str(RUST_BIN), "--serve"])
        child.kill()
        self.assertIsNotNone(child.proc.returncode)
        child.proc.stdin.close(); child.proc.stdout.close(); child.proc.stderr.close()


class 고정_입력과_표(unittest.TestCase):
    def test_고정_입력은_다시_만들어도_해시가_같다(self):
        import make_fixtures  # noqa: PLC0415

        lock = make_fixtures.generate()
        text = json.dumps(lock, ensure_ascii=False, indent=2) + "\n"
        self.assertEqual(text, make_fixtures.LOCK.read_text(encoding="utf-8"))
        self.assertEqual(lock["fixtureCount"], 24)
        self.assertEqual({fx["type"] for fx in lock["fixtures"]},
                         {"youtube", "text", "audio", "frames", "mixed", "photo_post"})
        self.assertTrue(all(fx["mediaBytesTotal"] <= 16 * 1024 * 1024 for fx in lock["fixtures"]))

    def test_Rust_MIME_기본표는_현재_Python과_같다(self):
        r = subprocess.run([sys.executable, str(ROOT / "scripts" / "gen_mime_defaults.py"), "--check"], capture_output=True)
        self.assertEqual(r.returncode, 0, r.stderr.decode())

    def test_Rust_고정_문구는_Python_원문과_같다(self):
        src = (ROOT / "src" / "lib.rs").read_text(encoding="utf-8")
        start = src.index("pub const PHOTO_POST_TEXT")
        block = src[start:src.index(");", start)]
        joined = "".join(json.loads(f'"{s}"') for s in __import__("re").findall(r'"((?:[^"\\]|\\.)*)"', block))
        self.assertEqual(joined, extract_places.PHOTO_POST_TEXT)


if __name__ == "__main__":
    unittest.main()
