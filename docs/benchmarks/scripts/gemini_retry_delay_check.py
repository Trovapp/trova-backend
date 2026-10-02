"""Gemini 재시도 대기 시간 확인(#7) — 실제 API를 부르지 않고, 응답을 가짜 503/429로 바꿔 call_gemini가 쉬는 시간만 기록한다.

사용: python3 gemini_retry_delay_check.py <extract_places.py 경로>
(수정 전 비교: git show 62e66b1:pipeline-test/extract_places.py > /tmp/old.py 처럼 꺼내서 경로로 넘긴다)
"""
import email.message, importlib.util, io, json, sys, urllib.error

path = sys.argv[1]
spec = importlib.util.spec_from_file_location("ep", path)
ep = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ep)


def run(code: int, headers: dict, body: dict | None = None) -> list[float]:
    sleeps: list[float] = []

    def fake_urlopen(request, timeout=None):
        msg = email.message.Message()
        for k, v in headers.items():
            msg[k] = v
        raise urllib.error.HTTPError(request.full_url, code, "fake", msg, io.BytesIO(json.dumps(body or {}).encode()))

    ep.urllib.request.urlopen = fake_urlopen
    ep.time.sleep = sleeps.append
    try:
        ep.call_gemini([{"text": "x"}], "m", "k", "check")
    except SystemExit:
        pass
    return sleeps


cases = {
    "503 계속(high demand)": run(503, {}),
    "503 + Retry-After 120": run(503, {"Retry-After": "120"}),
    "429 분당 한도(retryDelay 45s)": run(429, {}, {"error": {"message": "per minute", "details": [{"retryDelay": "45s"}]}}),
}
for name, sleeps in cases.items():
    print(f"{name}: 대기 {sleeps} 합 {sum(sleeps):.0f}초")
