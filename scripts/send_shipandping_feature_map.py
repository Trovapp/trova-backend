"""관리자가 배포본의 기능 지도를 한 번 등록한다."""
import http.client
import os
from pathlib import Path
import sys

def main():
    key = os.environ.get("SHIP_AND_PING_KEY", "").strip()
    if not key:
        return "SHIP_AND_PING_KEY 환경 변수가 필요합니다."
    path = Path(__file__).resolve().parents[1] / "src/main/resources/shipandping/feature-map.json"
    connection = http.client.HTTPSConnection("ship-and-ping-ingest.taetaeg527.workers.dev", timeout=10)
    try:
        connection.request("POST", "/v1/feature-map", path.read_bytes(), {
            "Authorization": "Bearer " + key,
            "Content-Type": "application/json",
        })
        status = connection.getresponse().status
        print(f"HTTP {status}")
        return 0 if status == 202 else 1
    except (OSError, ValueError, http.client.HTTPException):
        return "기능 지도 전송 실패"
    finally:
        connection.close()

if __name__ == "__main__":
    sys.exit(main())
