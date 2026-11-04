#!/usr/bin/env python3
"""고정 입력 24개와 실패 입력을 결정적으로 만든다(#312).

- 개인 자료·실제 키·URL 다운로드를 쓰지 않는다. 미디어는 SHAKE-256 바이트 흐름으로 만든 합성 파일이다
  (JPEG/MP3/WAV/M4A/PNG/WEBP 머리말만 흉내 낸 것 — 실제로 재생/디코드하지 않는다. parts 준비는 바이트만 읽는다).
- 같은 스크립트를 다시 돌리면 같은 바이트가 나온다. --verify 는 다시 만든 해시를 fixtures.lock.json 과 비교한다.
- 생성물은 fixtures/generated/ (git 제외), 해시 기록은 fixtures/fixtures.lock.json (커밋).

요청 파일: generated/requests/<id>.jsonl — 1줄 collect(후보 없음, COLLECT_PROMPT), 2줄 filter(후보 목록, FILTER_PROMPT).
경로는 generated/ 기준 상대 경로라 실행할 때 cwd 를 generated/ 로 둔다.
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import stat
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GEN = ROOT / "fixtures" / "generated"
LOCK = ROOT / "fixtures" / "fixtures.lock.json"
sys.path.insert(0, str(ROOT.parents[1] / "pipeline-test"))

import extract_places  # noqa: E402 — 실제 프롬프트를 그대로 쓴다

KIB = 1024
MIB = 1024 * 1024


def stream(label: str, size: int) -> bytes:
    return hashlib.shake_256(f"trova-312:{label}".encode()).digest(size)


def fake_jpeg(label: str, size: int) -> bytes:
    head = b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
    comment = b"\xff\xfe\x00\x10trova-synthetic"[:20]
    tail = b"\xff\xd9"
    return head + comment + stream(label, size - len(head) - len(comment) - len(tail)) + tail


def fake_png(label: str, size: int) -> bytes:
    head = b"\x89PNG\r\n\x1a\n"
    return head + stream(label, size - len(head))


def fake_webp(label: str, size: int) -> bytes:
    head = b"RIFF" + (size - 8).to_bytes(4, "little") + b"WEBPVP8 "
    return head + stream(label, size - len(head))


def fake_mp3(label: str, size: int) -> bytes:
    id3 = b"ID3\x04\x00\x00\x00\x00\x00\x00"
    frame_len = 417  # MPEG-1 Layer III 128kbps 44.1kHz 프레임 길이
    body = bytearray()
    noise = stream(label, size)
    pos = 0
    while len(body) < size - len(id3):
        body += b"\xff\xfb\x90\x64" + noise[pos:pos + frame_len - 4]
        pos += frame_len - 4
        if pos + frame_len > len(noise):
            pos = 0
    return id3 + bytes(body[: size - len(id3)])


def fake_wav(label: str, size: int) -> bytes:
    data_len = size - 44
    head = (b"RIFF" + (size - 8).to_bytes(4, "little") + b"WAVEfmt " + (16).to_bytes(4, "little")
            + (1).to_bytes(2, "little") + (1).to_bytes(2, "little") + (16000).to_bytes(4, "little")
            + (32000).to_bytes(4, "little") + (2).to_bytes(2, "little") + (16).to_bytes(2, "little")
            + b"data" + data_len.to_bytes(4, "little"))
    return head + stream(label, data_len)


def fake_m4a(label: str, size: int) -> bytes:
    head = (24).to_bytes(4, "big") + b"ftypM4A \x00\x00\x00\x00M4A isom"
    return head + stream(label, size - len(head))


def fake_bin(label: str, size: int) -> bytes:
    return stream(label, size)


MAKERS = {"jpg": fake_jpeg, "png": fake_png, "webp": fake_webp, "mp3": fake_mp3, "wav": fake_wav, "m4a": fake_m4a,
          "bin": fake_bin}

# (상대 경로, 만드는 형식, 크기)
MEDIA: list[tuple[str, str, int]] = []


def media(path: str, kind: str, size: int) -> str:
    if not any(m[0] == path for m in MEDIA):
        MEDIA.append((path, kind, size))
    return path


def frames(prefix: str, count: int, base_size: int, name_fmt: str = "frame_{:03d}.jpg") -> list[str]:
    out = []
    for i in range(1, count + 1):
        size = base_size + (i * 7919) % (base_size // 3)  # 장마다 크기를 조금씩 다르게
        out.append(media(f"{prefix}/{name_fmt.format(i)}", "jpg", size))
    return out


YT = "https://www.youtube.com/watch?v="
LONG_TRANSCRIPT = "\n".join(
    f"[{m:02d}:{s:02d}] 오늘은 부산 해운대에서 {i}번째 맛집을 찾아갑니다. 웨이팅이 30분 정도 있었고 돼지국밥이 9,000원이에요."
    for i, (m, s) in enumerate(((i // 60, i % 60) for i in range(0, 2400, 1)))
)  # 약 260KB

# 사진 게시물(인스타) 1080px 사진 정도 크기
PHOTOS10 = [media(f"media/photos10/photo-{i:02d}.jpg", "jpg", 140 * KIB + (i * 15013) % (120 * KIB)) for i in range(10)]

FIXTURES: list[dict] = [
    # 유튜브 링크 참조 — 미디어를 읽지 않는다
    {"id": "yt-01", "type": "youtube", "note": "링크만",
     "base": {"video_uri": YT + "aaaaaaaaaaa"}, "candidates": ["해운대", "광안리"]},
    {"id": "yt-02", "type": "youtube", "note": "링크+게시물 설명",
     "base": {"video_uri": YT + "bbbbbbbbbbb", "post_description": "부산 1박2일 코스 🏷 해운대 블루라인파크\n주소: 부산 해운대구 청사포로 116"},
     "candidates": ["블루라인파크", "청사포"]},
    {"id": "yt-03", "type": "youtube", "note": "Unicode URL·이모지·따옴표",
     "base": {"video_uri": "https://youtu.be/ccccccccccc?si=한글&t=12", "post_description": "서울 \"숨은\" 카페 ☕️ \\백슬래시\\ 탭\t끝",
              "transcript": "제주 애월 카페거리 😀"},
     "candidates": ["애월 카페거리", "\"숨은\" 카페"]},
    {"id": "yt-04", "type": "youtube", "note": "링크+빈 설명·빈 자막(건너뜀)",
     "base": {"video_uri": YT + "ddddddddddd", "post_description": "", "transcript": "", "audio_path": None, "frame_paths": None},
     "candidates": []},
    # 텍스트/자막
    {"id": "txt-01", "type": "text", "note": "한글 자막",
     "base": {"transcript": "강릉 안목해변 커피거리에서 시작해서 초당순두부 마을로 갔어요."}, "candidates": ["안목해변", "초당순두부 마을"]},
    {"id": "txt-02", "type": "text", "note": "영문 자막",
     "base": {"transcript": "We started at Gyeongbokgung Palace, then walked to Bukchon Hanok Village."},
     "candidates": ["Gyeongbokgung Palace", "Bukchon Hanok Village"]},
    {"id": "txt-03", "type": "text", "note": "빈 선택 필드·제어 문자",
     "base": {"transcript": "줄\r\n바꿈\u0001제어\u007f문자\u2028구분", "post_description": None, "video_uri": None, "photo_post": False},
     "candidates": ["\u0001", "백\\슬"]},
    {"id": "txt-04", "type": "text", "note": "긴 자막(약 260KB)",
     "base": {"transcript": LONG_TRANSCRIPT}, "candidates": [f"맛집{i}" for i in range(40)]},
    # 오디오
    {"id": "aud-01", "type": "audio", "note": "작은 mp3 8KiB",
     "base": {"audio_path": media("media/aud-01/audio.mp3", "mp3", 8 * KIB)}, "candidates": ["남포동"]},
    {"id": "aud-02", "type": "audio", "note": "중간 m4a 480KiB·한글 파일명",
     "base": {"audio_path": media("media/aud-02/여행 음성.m4a", "m4a", 480 * KIB), "transcript": "자막도 같이"},
     "candidates": ["감천문화마을"]},
    {"id": "aud-03", "type": "audio", "note": "큰 mp3 4MiB",
     "base": {"audio_path": media("media/aud-03/audio.mp3", "mp3", 4 * MIB)}, "candidates": ["태종대", "흰여울문화마을"]},
    {"id": "aud-04", "type": "audio", "note": "확장자 없음 8MiB(폴백 audio/mp3)",
     "base": {"audio_path": media("media/aud-04/audio", "bin", 8 * MIB)}, "candidates": ["송도해수욕장"]},
    # 프레임
    {"id": "frm-01", "type": "frames", "note": "1장",
     "base": {"frame_paths": frames("media/frm-01", 1, 48 * KIB)}, "candidates": ["성산일출봉"]},
    {"id": "frm-02", "type": "frames", "note": "4장",
     "base": {"frame_paths": frames("media/frm-02", 4, 48 * KIB)}, "candidates": ["우도", "섭지코지"]},
    {"id": "frm-03", "type": "frames", "note": "8장·한글 폴더/파일명",
     "base": {"frame_paths": frames("media/한글 폴더/frm-03", 8, 56 * KIB, "프레임_{:03d}.jpg")}, "candidates": ["협재해수욕장"]},
    {"id": "frm-04", "type": "frames", "note": "8장·확장자 섞임·역순 배열(순서 보존)",
     "base": {"frame_paths": [
         media("media/frm-04/f8.JPG", "jpg", 40 * KIB), media("media/frm-04/f7.jpeg", "jpg", 41 * KIB),
         media("media/frm-04/f6.png", "png", 42 * KIB), media("media/frm-04/f5.webp", "webp", 43 * KIB),
         media("media/frm-04/f4", "jpg", 44 * KIB), media("media/frm-04/f3.jpe", "jpg", 45 * KIB),
         media("media/frm-04/./f2.jpg", "jpg", 46 * KIB), media("media/frm-04//f1.jpg", "jpg", 47 * KIB)]},
     "candidates": ["오설록", "카멜리아힐"]},
    # 오디오+프레임
    {"id": "mix-01", "type": "mixed", "note": "오디오+프레임+자막",
     "base": {"audio_path": media("media/mix-01/audio.mp3", "mp3", 960 * KIB), "frame_paths": frames("media/mix-01", 8, 52 * KIB),
              "transcript": "전주 한옥마을 투어"}, "candidates": ["전주 한옥마을", "경기전"]},
    {"id": "mix-02", "type": "mixed", "note": "오디오+프레임+설명+후보",
     "base": {"audio_path": media("media/mix-02/audio.mp3", "mp3", 1536 * KIB), "frame_paths": frames("media/mix-02", 8, 60 * KIB),
              "post_description": "경주 황리단길 🏷 교리김밥\n주소: 경북 경주시 탑리3길 2"}, "candidates": ["황리단길", "교리김밥", "첨성대"]},
    {"id": "mix-03", "type": "mixed", "note": "큰 오디오 4MiB+프레임 8장+특수문자 후보",
     "base": {"audio_path": media("media/aud-03/audio.mp3", "mp3", 4 * MIB), "frame_paths": frames("media/mix-03", 8, 64 * KIB),
              "post_description": "여수 밤바다 🌃", "transcript": "낭만포차 거리"},
     "candidates": ["낭만포차 \"거리\"", "a\\b", "😀 이모지", "tab\tchar"]},
    {"id": "mix-04", "type": "mixed", "note": "wav+프레임·한글 폴더·빈 후보 목록",
     "base": {"audio_path": media("media/한글 폴더/mix-04/녹음.wav", "wav", 1 * MIB), "frame_paths": frames("media/한글 폴더/mix-04", 4, 50 * KIB)},
     "candidates": []},
    # 사진 게시물
    {"id": "pho-01", "type": "photo_post", "note": "사진 1장+설명",
     "base": {"frame_paths": [media("media/pho-01/photo-00.jpg", "jpg", 210 * KIB)], "photo_post": True,
              "post_description": "가평 드라이브 코스 📍 남이섬"}, "candidates": ["남이섬"]},
    {"id": "pho-02", "type": "photo_post", "note": "사진 10장(올린 순서)",
     "base": {"frame_paths": PHOTOS10, "photo_post": True, "post_description": "양평 코스표 1→10"},
     "candidates": [f"코스{i}" for i in range(1, 11)]},
    {"id": "pho-03", "type": "photo_post", "note": "photo_post 이지만 사진 0장(문구 없음)",
     "base": {"frame_paths": [], "photo_post": True, "post_description": "사진 없는 게시물"}, "candidates": ["북한강변길"]},
    {"id": "pho-04", "type": "photo_post", "note": "사진 3장·한글 경로·자막·특수문자 후보",
     "base": {"frame_paths": [media(f"media/한글 폴더/pho-04/사진-{i}.jpg", "jpg", 180 * KIB + i * 999) for i in range(3)],
              "photo_post": True, "transcript": "릴스 음성 없음", "post_description": "속초 🏷 \"중앙시장\""},
     "candidates": ["속초중앙시장", "영금정 '정자'"]},
]

# 실패·경계 입력(성능 24개와 별도). expect 는 기대 결과(오류 종류 또는 "ok").
FAILURE_MEDIA = [
    ("failures/ok.mp3", "mp3", 4 * KIB), ("failures/ok.jpg", "jpg", 4 * KIB),
    ("failures/ab:c.png#x.mp3", "mp3", 2 * KIB), ("failures/data:x,y.mp3", "mp3", 2 * KIB),
    ("failures/.mp3", "mp3", 2 * KIB), ("failures/x.mp3.gz", "mp3", 2 * KIB),
    ("failures/big-a.bin", "bin", 9 * MIB), ("failures/big-b.bin", "bin", 8 * MIB),
    ("failures/no-read.mp3", "mp3", 2 * KIB),
]
P = "실패 시험"
FAILURES: list[dict] = [
    {"id": "f01-missing-audio", "expect": "not_found", "raw": {"prompt": P, "audio_path": "failures/없음.mp3"}},
    {"id": "f02-missing-2nd-frame", "expect": "not_found",
     "raw": {"prompt": P, "frame_paths": ["failures/ok.jpg", "failures/없는-프레임.jpg", "failures/ok.jpg"]}},
    {"id": "f03-audio-is-directory", "expect": "is_directory", "raw": {"prompt": P, "audio_path": "failures"}},
    {"id": "f04-empty-audio-path", "expect": "is_directory", "raw": {"prompt": P, "audio_path": ""}},
    {"id": "f05-trailing-slash-file", "expect": "ok", "raw": {"prompt": P, "audio_path": "failures/ok.mp3/"}},
    {"id": "f06-not-a-directory", "expect": "not_a_directory", "raw": {"prompt": P, "frame_paths": ["failures/ok.mp3/x.jpg"]}},
    {"id": "f07-permission-denied", "expect": "permission_denied", "raw": {"prompt": P, "audio_path": "failures/no-read.mp3"}},
    {"id": "f08-nul-in-path", "expect": "invalid_path", "raw": {"prompt": P, "audio_path": "failures/ok\u0000.mp3"}},
    {"id": "f09-too-large", "expect": "input_too_large",
     "raw": {"prompt": P, "audio_path": "failures/big-a.bin", "frame_paths": ["failures/big-b.bin"]}},
    {"id": "f10-colon-fragment-name", "expect": "ok", "raw": {"prompt": P, "audio_path": "failures/ab:c.png#x.mp3"}},
    {"id": "f11-data-url-name", "expect": "ok", "raw": {"prompt": P, "audio_path": "failures/data:x,y.mp3"}},
    {"id": "f12-dotfile-name", "expect": "ok", "raw": {"prompt": P, "audio_path": "failures/.mp3"}},
    {"id": "f13-gz-suffix", "expect": "ok", "raw": {"prompt": P, "frame_paths": ["failures/x.mp3.gz"]}},
    {"id": "f14-wrong-type", "expect": "invalid_manifest", "raw": {"prompt": P, "photo_post": "yes"}},
    {"id": "f15-unknown-key", "expect": "invalid_manifest", "raw": {"prompt": P, "audio": "failures/ok.mp3"}},
    {"id": "f16-missing-prompt", "expect": "invalid_manifest", "raw": {"transcript": "x"}},
    {"id": "f17-non-string-candidate", "expect": "invalid_manifest", "raw": {"prompt": P, "candidates": ["a", 1]}},
    {"id": "f18-top-level-array", "expect": "invalid_manifest", "line": "[]"},
    {"id": "f19-broken-json", "expect": "invalid_json", "line": "{\"prompt\": \"x\""},
    {"id": "f20-nan", "expect": "invalid_json", "line": "{\"prompt\": \"x\", \"candidates\": [NaN]}"},
    {"id": "f21-lone-surrogate", "expect": "invalid_json", "line": "{\"prompt\": \"\\ud800\"}"},
    {"id": "f22-too-deep", "expect": "invalid_json", "line": "{\"prompt\":\"x\",\"candidates\":" + "[" * 127 + "]" * 127 + "}"},
    {"id": "f23-invalid-utf8", "expect": "invalid_json", "line_bytes": b"{\"prompt\": \"\xff\xfe\"}"},
    {"id": "f24-bom", "expect": "invalid_json", "line_bytes": b"\xef\xbb\xbf{\"prompt\": \"x\"}"},
    {"id": "f25-empty-line", "expect": "invalid_json", "line": ""},
    {"id": "f26-duplicate-key-last-wins", "expect": "ok", "line": "{\"prompt\": \"a\", \"prompt\": \"b\"}"},
    {"id": "f27-number-overflow", "expect": "invalid_json", "line": "{\"prompt\": \"x\", \"candidates\": [1e400]}"},
]


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def request_lines(fx: dict) -> list[bytes]:
    collect = dict(fx["base"], prompt=extract_places.COLLECT_PROMPT)
    filt = dict(fx["base"], prompt=extract_places.FILTER_PROMPT, candidates=fx["candidates"])
    return [json.dumps(collect, ensure_ascii=False).encode("utf-8"), json.dumps(filt, ensure_ascii=False).encode("utf-8")]


def failure_line(case: dict) -> bytes:
    if "line_bytes" in case:
        return case["line_bytes"]
    if "line" in case:
        return case["line"].encode("utf-8")
    return json.dumps(case["raw"], ensure_ascii=False).encode("utf-8")


def generate() -> dict:
    if GEN.exists():
        # 권한을 뺀 파일도 지울 수 있게 먼저 되돌린다.
        for p in GEN.rglob("*"):
            try:
                os.chmod(p, 0o755 if p.is_dir() else 0o644)
            except OSError:
                pass
        shutil.rmtree(GEN)
    files: dict[str, dict] = {}
    for rel, kind, size in MEDIA + FAILURE_MEDIA:
        norm = str(Path(rel))  # pathlib 정규화(./, // 제거)한 실제 위치
        if norm in files:
            continue
        data = MAKERS[kind](rel, size)
        assert len(data) == size, (rel, len(data), size)
        target = GEN / norm
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        files[norm] = {"bytes": size, "sha256": sha256(data), "kind": kind}
    os.chmod(GEN / "failures/no-read.mp3", 0)

    req_dir = GEN / "requests"
    req_dir.mkdir(parents=True)
    fixtures_out = []
    for fx in FIXTURES:
        lines = request_lines(fx)
        body = b"\n".join(lines) + b"\n"
        (req_dir / f"{fx['id']}.jsonl").write_bytes(body)
        used = []
        for p in ([fx["base"]["audio_path"]] if fx["base"].get("audio_path") else []) + list(fx["base"].get("frame_paths") or []):
            used.append({"path": p, **files[str(Path(p))]})
        total = sum(u["bytes"] for u in used)
        assert total <= 16 * MIB, (fx["id"], total)
        fixtures_out.append({
            "id": fx["id"], "type": fx["type"], "note": fx["note"],
            "requestFile": f"requests/{fx['id']}.jsonl", "requestSha256": sha256(body),
            "collectRequestSha256": sha256(lines[0]), "filterRequestSha256": sha256(lines[1]),
            "mediaOrder": used, "mediaBytesTotal": total, "candidates": fx["candidates"],
        })
    fail_dir = GEN / "failures-requests"
    fail_dir.mkdir()
    failures_out = []
    for case in FAILURES:
        line = failure_line(case)
        (fail_dir / f"{case['id']}.jsonl").write_bytes(line + b"\n")
        failures_out.append({"id": case["id"], "expect": case["expect"], "requestFile": f"failures-requests/{case['id']}.jsonl",
                             "requestSha256": sha256(line)})
    return {
        "generator": "tools/rust-parts-pilot/scripts/make_fixtures.py",
        "byteSource": "SHAKE-256('trova-312:<path>') 합성 바이트 — 개인 자료·다운로드 없음",
        "promptSha256": {"collect": sha256(extract_places.COLLECT_PROMPT.encode()), "filter": sha256(extract_places.FILTER_PROMPT.encode())},
        "fixtureCount": len(fixtures_out),
        "fixtures": fixtures_out,
        "failureCases": failures_out,
        "files": dict(sorted(files.items())),
    }


def main() -> int:
    lock = generate()
    text = json.dumps(lock, ensure_ascii=False, indent=2) + "\n"
    if "--verify" in sys.argv:
        if not LOCK.exists() or LOCK.read_text(encoding="utf-8") != text:
            print("재생성 결과가 fixtures.lock.json 과 다르다", file=sys.stderr)
            return 1
        print(f"재생성 일치: 고정 입력 {lock['fixtureCount']}개, 실패 입력 {len(lock['failureCases'])}개, 파일 {len(lock['files'])}개")
        return 0
    LOCK.write_text(text, encoding="utf-8")
    print(f"wrote {LOCK} ({lock['fixtureCount']} fixtures, {len(lock['files'])} files)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
