#!/usr/bin/env python3
"""운영 서버에서 릴스 처리의 단계·명령별 CPU를 잰다(#350, 사용자 선택 1, 2026-11-09).

usage:
  PROD_SSH=ubuntu@<host> PROD_SSH_KEY=<키> python3 monitoring/limit/reel_cpu_stages.py --out <새 폴더> \\
      [--gap 90] <릴스 URL> [<릴스 URL> ...]

- 서버의 운영 스크립트(/opt/trova-backend/pipeline-test)를 그대로 불러 내려받기 → 오디오 → 프레임만 실행한다.
  Gemini·DB·앱은 쓰지 않는다(한도·사용자 데이터에 닿지 않음). 받은 파일은 임시 폴더에 두고 끝나면 지운다.
- subprocess.run을 감싸 명령(yt-dlp·ffmpeg·ffprobe)마다 걸린 시간과 자식 CPU(사용자+시스템)를 남긴다.
- 서버 전체 steal·사용 CPU도 앞뒤로 읽어 같은 시간에 다른 작업이 있었는지 본다.
- 인스타그램이 데이터센터 IP를 막지 않게 URL 사이에 --gap초 쉰다.
"""
import argparse
import base64
import json
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
from prod_run import ssh  # noqa: E402

REMOTE = r'''
import json, os, resource, shutil, subprocess, sys, tempfile, time
from pathlib import Path
sys.path.insert(0, "/opt/trova-backend/pipeline-test")
import run_pipeline as rp
import frames as frames_mod
from stage_timer import StageTimer

def cpu_children():
    u = resource.getrusage(resource.RUSAGE_CHILDREN)
    return u.ru_utime + u.ru_stime

def proc_stat():
    f = open("/proc/stat").readline().split()[1:]
    v = [int(x) for x in f]
    return {"busy": sum(v[:3]) + sum(v[5:7]), "idle": v[3] + v[4], "steal": v[7]}

commands = []
stage_now = ["-"]
real_run = subprocess.run
def traced(cmd, *a, **k):
    c0, t0 = cpu_children(), time.perf_counter()
    try:
        return real_run(cmd, *a, **k)
    finally:
        name = Path(str(cmd[0])).name
        detail = ""
        if name == "ffmpeg":
            detail = "audio" if "-vn" in cmd else ("frames-seek" if "-ss" in cmd else "frames-full")
        elif name == "ffprobe":
            detail = "packets" if "packet=pts_time,flags" in " ".join(map(str, cmd)) else "probe"
        commands.append({"stage": stage_now[0], "cmd": name, "detail": detail,
                         "wallMs": round((time.perf_counter() - t0) * 1000),
                         "cpuMs": round((cpu_children() - c0) * 1000)})
subprocess.run = traced

url = sys.argv[1]
work = Path(tempfile.mkdtemp(prefix="reel-cpu-"))
timer = StageTimer()
s0, t0 = proc_stat(), time.time()
out = {"url": url}
try:
    stage_now[0] = "download"
    with timer.stage("download"):
        video, title, desc, imgs = rp.instagram_fetch(url, work / "download")
    if video is None:
        out["error"] = "사진 게시물"
    else:
        out["videoBytes"] = video.stat().st_size
        out["videoExt"] = video.suffix
        stage_now[0] = "probe"
        p = real_run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                      "stream=codec_name,width,height,r_frame_rate:format=duration", "-of", "json", str(video)],
                     capture_output=True, text=True)
        out["video"] = json.loads(p.stdout or "{}")
        stage_now[0] = "audio"
        with timer.stage("audio"):
            if rp.has_audio_stream(video):
                rp.extract_audio(video, work / "audio.mp3")
        stage_now[0] = "frames"
        with timer.stage("frames"):
            fr = frames_mod.extract_frames(video, work / "frames", max_frames=rp.MAX_FRAMES)
        out["frames"] = len(fr)
except SystemExit as e:
    out["error"] = str(e)[:300]
finally:
    s1 = proc_stat()
    out.update({"wallS": round(time.time() - t0, 1), "stageWallMs": timer.as_dict(), "stageCpuMs": timer.cpu_dict(),
                "commands": commands,
                "serverTicks": {k: s1[k] - s0[k] for k in s0}})
    shutil.rmtree(work, ignore_errors=True)
print("RESULT " + json.dumps(out, ensure_ascii=False))
'''


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    ap.add_argument('--gap', type=int, default=90)
    ap.add_argument('urls', nargs='+')
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=False)
    script = base64.b64encode(REMOTE.encode()).decode()
    results = []
    for i, url in enumerate(args.urls):
        if i:
            time.sleep(args.gap)
        r = ssh(f'cd /tmp && echo {script} | base64 -d > /tmp/reel_cpu_probe.py && python3 /tmp/reel_cpu_probe.py "{url}"; '
                f'rm -f /tmp/reel_cpu_probe.py')
        line = next((x for x in r.stdout.splitlines() if x.startswith('RESULT ')), None)
        res = json.loads(line[7:]) if line else {'url': url, 'error': r.stderr.strip()[-300:]}
        results.append(res)
        print(json.dumps({k: res.get(k) for k in ('url', 'wallS', 'stageWallMs', 'stageCpuMs', 'error')},
                         ensure_ascii=False), flush=True)
    (out / 'result.json').write_text(json.dumps(results, indent=2, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    sys.exit(main())
