#!/usr/bin/env python3
"""운영 DB를 읽기 전용으로만 조회한다(#334).

Supabase 연결 풀러는 접속 옵션(PGOPTIONS의 default_transaction_read_only)을 무시한다.
그래서 접속 옵션에 기대지 않고, 입력 SQL 전체를 읽기 전용 트랜잭션 하나로 감싼 뒤
읽기 전용이 실제로 켜졌는지 DB 안에서 확인하고 마지막에 ROLLBACK한다.

사용:
    PROD_DB_ENV_FILE=<운영 DB 환경 파일> python3 monitoring/prod_readonly_psql.py <<'SQL'
    select count(*) from users;
    SQL

- 환경 파일에서 SPRING_DATASOURCE_URL·USERNAME·PASSWORD만 읽는다. 자격증명은 출력하지 않는다.
- 트랜잭션을 끝내거나 설정을 바꾸는 문장(COMMIT·BEGIN·SET 등)과 psql 메타 명령(\\로 시작)은 거부한다.
- psql 위치는 PSQL 환경 변수 → PATH → Homebrew libpq 순으로 찾는다.
"""

import os
import re
import shutil
import subprocess
import sys
import urllib.parse

# 감싼 트랜잭션을 빠져나가거나 읽기 전용을 끌 수 있는 문장.
FORBIDDEN = re.compile(
    r"\b(commit|end|rollback|abort|begin|start\s+transaction|savepoint|release|prepare\s+transaction|"
    r"set|reset|discard)\b",
    re.IGNORECASE,
)
STATEMENT_TIMEOUT = "15s"


def strip_comments_and_literals(sql):
    """키워드 검사용: 주석과 문자열·식별자 따옴표 안을 지운다(값 안의 'end' 같은 글자로 막히지 않게)."""
    sql = re.sub(r"--[^\n]*", " ", sql)
    sql = re.sub(r"/\*.*?\*/", " ", sql, flags=re.DOTALL)
    sql = re.sub(r"'(?:[^']|'')*'", "''", sql)
    sql = re.sub(r'"(?:[^"]|"")*"', '""', sql)
    return sql


def check_sql(sql):
    """거부 사유를 돌려준다. 문제없으면 None."""
    if not sql.strip():
        return "SQL이 비어 있습니다(표준 입력으로 넘기세요)."
    for line in sql.splitlines():
        if line.lstrip().startswith("\\"):
            return "psql 메타 명령(\\...)은 쓸 수 없습니다."
    if "$$" in sql:
        return "달러 따옴표($$) 블록은 쓸 수 없습니다."
    match = FORBIDDEN.search(strip_comments_and_literals(sql))
    if match:
        return f"트랜잭션·설정을 바꾸는 문장은 쓸 수 없습니다: {match.group(1).upper()}"
    return None


def wrap(sql):
    """입력 SQL을 읽기 전용 트랜잭션으로 감싼다. 읽기 전용이 아니면 DB 안에서 바로 멈춘다."""
    body = sql.strip()
    if not body.endswith(";"):
        body += ";"
    return (
        "begin transaction read only;\n"
        f"set local statement_timeout = '{STATEMENT_TIMEOUT}';\n"
        "do $guard$ begin\n"
        "  if current_setting('transaction_read_only') <> 'on' then\n"
        "    raise exception 'read-only transaction is not active';\n"
        "  end if;\n"
        "end $guard$;\n"
        f"{body}\n"
        "rollback;\n"
    )


def pg_env(env_file):
    """스프링 환경 파일을 psql 환경 변수로 바꾼다."""
    values = {}
    with open(env_file, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip().strip('"').strip("'")
    url = values.get("SPRING_DATASOURCE_URL", "")
    if url.startswith("jdbc:"):
        url = url[len("jdbc:"):]
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ("postgresql", "postgres") or not parsed.hostname:
        raise ValueError("SPRING_DATASOURCE_URL이 postgresql 주소가 아닙니다.")
    query = urllib.parse.parse_qs(parsed.query)
    return {
        "PGHOST": parsed.hostname,
        "PGPORT": str(parsed.port or 5432),
        "PGDATABASE": parsed.path.lstrip("/"),
        "PGUSER": values.get("SPRING_DATASOURCE_USERNAME") or (query.get("user") or [""])[0],
        "PGPASSWORD": values.get("SPRING_DATASOURCE_PASSWORD") or (query.get("password") or [""])[0],
        "PGSSLMODE": (query.get("sslmode") or ["require"])[0],
    }


def find_psql():
    for candidate in (os.environ.get("PSQL"), shutil.which("psql"), "/opt/homebrew/opt/libpq/bin/psql"):
        if candidate and os.path.exists(candidate):
            return candidate
    return None


def main():
    env_file = os.environ.get("PROD_DB_ENV_FILE")
    if not env_file:
        print("PROD_DB_ENV_FILE에 운영 DB 환경 파일 경로를 지정하세요.", file=sys.stderr)
        return 2
    sql = sys.stdin.read()
    reason = check_sql(sql)
    if reason:
        print(f"거부: {reason}", file=sys.stderr)
        return 2
    psql = find_psql()
    if not psql:
        print("psql을 찾지 못했습니다(brew install libpq 또는 PSQL 지정).", file=sys.stderr)
        return 2
    env = dict(os.environ)
    env.update(pg_env(env_file))
    env.pop("PGOPTIONS", None)
    result = subprocess.run(
        [psql, "-X", "-q", "-v", "ON_ERROR_STOP=1", "-A", "-F", " | "],
        input=wrap(sql), text=True, env=env,
    )
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
