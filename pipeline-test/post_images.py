"""인스타그램 사진 게시물(#196) — yt-dlp가 영상 없이 돌려준 게시물 정보에서 사진 주소를 고르고 받는다."""
from __future__ import annotations

import urllib.request
from pathlib import Path

# 게시물은 최대 20장 — 너무 많으면 Gemini 입력이 커져 느려지고 한도를 더 쓴다. 표지·코스 사진은 대개 앞쪽에 있다.
MAX_POST_IMAGES = 10


def _is_video(entry: dict) -> bool:
    return any((f.get("vcodec") or "none") != "none" for f in entry.get("formats") or [])


def post_image_urls(info: dict, limit: int = MAX_POST_IMAGES) -> list[str]:
    """사진마다 잘리지 않은 원본 주소 하나. thumbnail이 원본이고, thumbnails 첫째는 가운데를 잘라 글자가 빠질 수 있다."""
    entries = info.get("entries") or [info]
    urls: list[str] = []
    for entry in entries:
        if not entry or _is_video(entry):
            continue
        thumbs = entry.get("thumbnails") or []
        url = entry.get("thumbnail") or (thumbs[-1].get("url") if thumbs else None)
        if url and url not in urls:
            urls.append(url)
        if len(urls) >= limit:
            break
    return urls


def download_images(urls: list[str], out_dir: Path) -> list[Path]:
    """받다 실패한 사진은 건너뛴다 — 한 장 때문에 게시물 전체를 실패로 만들지 않는다."""
    out_dir.mkdir(parents=True, exist_ok=True)
    paths: list[Path] = []
    for i, url in enumerate(urls):
        path = out_dir / f"photo-{i:02d}.jpg"
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(request, timeout=20) as response:
                path.write_bytes(response.read())
            paths.append(path)
        except OSError:
            continue
    return paths
