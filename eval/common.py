"""측정 스크립트 공통 기능 — 로컬 서버 인증, 개발 DB 연결, 실행 기록(날짜·커밋·프롬프트 해시).

환경 변수(로컬 서버를 띄운 것과 같은 .env를 그대로 쓴다):
  EVAL_BASE_URL            측정할 서버 (기본 http://localhost:8080). localhost가 아니면 거부한다.
  EVAL_USER_ID             측정에 쓰는 테스트 계정 id (기본 1). 이 계정의 데이터만 만들고 지운다.
  JWT_SECRET               서버와 같은 비밀값 — 테스트 계정 토큰을 만든다.
  SPRING_DATASOURCE_URL / SPRING_DATASOURCE_USERNAME / SPRING_DATASOURCE_PASSWORD
                           서버가 쓰는 DB. EVAL_PROD_DB_HOST와 같은 호스트면 거부한다(운영 DB 보호).
  EVAL_PROD_DB_HOST        운영 DB 호스트(선택). 설정하면 실수로 운영 DB를 가리킬 때 멈춘다.
  EVAL_PROD_DB_USER        운영 DB 사용자 이름(선택). Supabase pooler는 운영·개발이 같은 호스트를 쓰고
                           사용자 이름(postgres.<프로젝트>)으로 나뉘어서, 호스트와 함께 비교해야 구분된다.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
PIPELINE_FILES = ["extract_places.py", "run_pipeline.py", "download.py", "frames.py",
                  "select_place_match.py", "verify_places.py", "generate_itinerary.py"]
DEFAULT_MODEL = "gemini-3.5-flash-lite"


def base_url() -> str:
    url = os.environ.get("EVAL_BASE_URL", "http://localhost:8080").rstrip("/")
    if not re.match(r"^http://(localhost|127\.0\.0\.1)(:\d+)?$", url):
        sys.exit(f"EVAL_BASE_URL={url} — 측정은 로컬 서버에서만 한다(운영 데이터 보호).")
    return url


def user_id() -> int:
    return int(os.environ.get("EVAL_USER_ID", "1"))


def token() -> str:
    import jwt  # pyjwt

    secret = os.environ.get("JWT_SECRET", "").strip('"')
    if not secret:
        sys.exit("JWT_SECRET가 없다 — 로컬 서버 .env를 불러온 뒤 실행한다.")
    now = int(time.time())
    return jwt.encode({"sub": str(user_id()), "iat": now, "exp": now + 3600}, secret, algorithm="HS256")


def api(method: str, path: str, body=None, timeout: float = 120):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base_url() + path, method=method, data=data, headers={
        "Authorization": "Bearer " + token(), "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw.decode(errors="replace")[:300]


def db():
    import psycopg

    url = os.environ.get("SPRING_DATASOURCE_URL", "")
    m = re.match(r"jdbc:postgresql://([^:/]+):?(\d+)?/([^?]+)", url)
    if not m:
        sys.exit("SPRING_DATASOURCE_URL가 없거나 형식이 다르다.")
    host = m.group(1)
    prod_host = os.environ.get("EVAL_PROD_DB_HOST")
    prod_user = os.environ.get("EVAL_PROD_DB_USER")
    db_user = os.environ.get("SPRING_DATASOURCE_USERNAME")
    # 사용자 이름을 모르면 호스트만으로 판단한다(같은 pooler를 쓰는 개발 DB도 멈추지만 안전한 쪽).
    if prod_host and host == prod_host and (not prod_user or db_user == prod_user):
        sys.exit("운영 DB를 가리키고 있다 — 측정은 개발 DB에서만 한다.")
    conn = psycopg.connect(host=host, port=m.group(2) or 5432, dbname=m.group(3),
                           user=os.environ["SPRING_DATASOURCE_USERNAME"],
                           password=os.environ["SPRING_DATASOURCE_PASSWORD"], sslmode="require")
    conn.autocommit = True
    return conn


def git_commit() -> str:
    out = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=REPO_ROOT, capture_output=True, text=True)
    dirty = subprocess.run(["git", "status", "--porcelain", "--", "src", "pipeline-test"], cwd=REPO_ROOT,
                           capture_output=True, text=True).stdout.strip()
    return out.stdout.strip() + ("-dirty" if dirty else "")


def pipeline_hashes(pipeline_dir: Path) -> dict:
    """서버가 실제로 실행하는 파이프라인 스크립트의 해시. 운영 서버 파일과 비교해 같은 설정인지 확인한다."""
    hashes = {}
    for name in PIPELINE_FILES:
        p = pipeline_dir / name
        if p.exists():
            hashes[name] = hashlib.sha256(p.read_bytes()).hexdigest()[:16]
    return hashes


def new_result_dir(kind: str) -> Path:
    stamp = dt.datetime.now().strftime("%Y-%m-%d_%H%M")
    d = Path(__file__).resolve().parent / kind / "results" / f"{stamp}_{git_commit()}"
    d.mkdir(parents=True, exist_ok=True)
    return d


def write_meta(result_dir: Path, extra: dict) -> None:
    pipeline_dir = Path(os.environ.get("EVAL_PIPELINE_DIR", REPO_ROOT / "pipeline-test"))
    meta = {
        "date": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
        "commit": git_commit(),
        "server": base_url(),
        "model": os.environ.get("GEMINI_MODEL", DEFAULT_MODEL),
        "pipeline_dir": str(pipeline_dir),
        "pipeline_sha256_16": pipeline_hashes(pipeline_dir),
        **extra,
    }
    (result_dir / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2))
