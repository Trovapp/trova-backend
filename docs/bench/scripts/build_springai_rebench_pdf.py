"""Spring AI 2 전환 전후 Gemini 클라이언트 재측정 PDF(#422).
저장된 JSON(docs/bench/results/client-resource-*.json)과 캡처 PNG만 읽는다. API·서버·DB에 접근하지 않는다.
의존성(내보내기 전용 venv): reportlab, matplotlib. --font에 임베딩 가능한 한글 TTF.
"""
import argparse
import json
import statistics
import random
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import Image, PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

BENCH = Path(__file__).resolve().parents[1]


def pct(values, p):
    s = sorted(values)
    if not s:
        return float("nan")
    k = (len(s) - 1) * p
    f, c = int(k), min(int(k) + 1, len(s) - 1)
    return s[f] + (s[c] - s[f]) * (k - f)


def stats(rows, key="milliseconds"):
    ok = [r[key] for r in rows if r.get("success")]
    return {
        "n": len(rows), "ok": len(ok), "p50": pct(ok, .5), "p95": pct(ok, .95), "p99": pct(ok, .99),
        "mean": statistics.fmean(ok) if ok else float("nan"), "min": min(ok) if ok else float("nan"), "max": max(ok) if ok else float("nan"),
    }


def paired_bootstrap(a, b, seed=422, iters=5000):
    """같은 라운드의 (b - a) 차이 중앙값과 bootstrap 중앙 95% 범위."""
    diffs = [y - x for x, y in zip(a, b)]
    rnd = random.Random(seed)
    meds = []
    for _ in range(iters):
        sample = [diffs[rnd.randrange(len(diffs))] for _ in diffs]
        meds.append(statistics.median(sample))
    meds.sort()
    return statistics.median(diffs), meds[int(len(meds) * .025)], meds[int(len(meds) * .975)]


def load(path):
    data = json.loads(Path(path).read_text())
    rows = [r for r in data["results"] if not r["warmup"]]
    by = {c: [r for r in rows if r["client"] == c] for c in ("rest", "spring-ai")}
    return data, by


def chart(by, title, out, font):
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.2))
    names = {"rest": "REST", "spring-ai": "Spring AI 2"}
    for ax, (key, label) in zip(axes, (("milliseconds", "응답 시간(ms)"), ("cpuMillis", "호출당 CPU 시간(ms)"))):
        for i, c in enumerate(("rest", "spring-ai")):
            vals = [r[key] for r in by[c] if r.get("success")]
            ax.scatter([i + (random.Random(i).random() - .5) * .25 for _ in vals], vals, s=14, alpha=.6)
            ax.hlines(statistics.median(vals), i - .3, i + .3, colors="black", linewidth=1.5)
        ax.set_xticks([0, 1]); ax.set_xticklabels([names["rest"], names["spring-ai"]], fontproperties=font)
        ax.set_title(label, fontproperties=font, fontsize=10)
        ax.grid(axis="y", alpha=.3)
    fig.suptitle(title, fontproperties=font, fontsize=11)
    fig.tight_layout(); fig.savefig(out, dpi=160); plt.close(fig)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--font", default="/System/Library/Fonts/Supplemental/AppleGothic.ttf")
    ap.add_argument("--short", default=str(BENCH / "results/client-resource-short-20261113.json"))
    ap.add_argument("--long", default=str(BENCH / "results/client-resource-long-20261113.json"))
    ap.add_argument("--captures", default=str(BENCH / "results/springai-rebench-20261113"))
    ap.add_argument("--output", default=str(BENCH / "reports/trova-springai-rebench-20261113.pdf"))
    args = ap.parse_args()

    pdfmetrics.registerFont(TTFont("K", args.font))
    fp = font_manager.FontProperties(fname=args.font)
    body = ParagraphStyle("b", fontName="K", fontSize=9.5, leading=14)
    small = ParagraphStyle("s", fontName="K", fontSize=8, leading=11, textColor=colors.HexColor("#555555"))
    h1 = ParagraphStyle("h1", fontName="K", fontSize=16, leading=20, spaceAfter=6)
    h2 = ParagraphStyle("h2", fontName="K", fontSize=12, leading=16, spaceBefore=10, spaceAfter=4)
    cap = Path(args.captures)
    tmp = Path(args.output).parent / "_tmp"; tmp.mkdir(parents=True, exist_ok=True)

    runs = {}
    for kind, path in (("short", args.short), ("long", args.long)):
        data, by = load(path)
        runs[kind] = (data, by)

    def table(rows, widths=None):
        t = Table(rows, colWidths=widths, repeatRows=1)
        t.setStyle(TableStyle([
            ("FONTNAME", (0, 0), (-1, -1), "K"), ("FONTSIZE", (0, 0), (-1, -1), 8),
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#eeeeee")),
            ("GRID", (0, 0), (-1, -1), .3, colors.HexColor("#999999")),
            ("ALIGN", (1, 1), (-1, -1), "RIGHT"), ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ]))
        return t

    def img(path, width=170 * mm):
        p = Path(path)
        if not p.exists():
            return Paragraph(f"(캡처 없음: {p.name})", small)
        from PIL import Image as PILImage
        w, h = PILImage.open(p).size
        return Image(str(p), width=width, height=width * h / w)

    story = []
    story.append(Paragraph("Trova — Spring AI 2 전환 전후 Gemini 클라이언트 재측정", h1))
    story.append(Paragraph("2026-11-13 · 로컬 Mac 측정(클라이언트만) + 운영 기록(api_call_logs, Grafana Cloud) · 이슈 #422", small))
    story.append(Spacer(1, 6))

    # 결론
    s_short = {c: stats(runs["short"][1][c]) for c in ("rest", "spring-ai")}
    s_long = {c: stats(runs["long"][1][c]) for c in ("rest", "spring-ai")}
    boot = {}
    for kind in ("short", "long"):
        by = runs[kind][1]
        a = {r["round"]: r["milliseconds"] for r in by["rest"] if r.get("success")}
        b = {r["round"]: r["milliseconds"] for r in by["spring-ai"] if r.get("success")}
        common = sorted(set(a) & set(b))
        boot[kind] = paired_bootstrap([a[i] for i in common], [b[i] for i in common]) + (len(common),)

    def verdict(kind):
        med, lo, hi, n = boot[kind]
        sign = "Spring AI 2가 빠름" if hi < 0 else ("REST가 빠름" if lo > 0 else "차이를 말할 수 없음(범위가 0 포함)")
        return f"{'짧은' if kind == 'short' else '긴'} 프롬프트: 같은 라운드 차이(Spring AI - REST) 중앙값 {med:+.0f}ms, bootstrap 95% 범위 {lo:+.0f}~{hi:+.0f}ms (짝 {n}개) → {sign}"

    story.append(Paragraph("<b>한 줄 결론.</b> " + verdict("short") + ". " + verdict("long") + ". "
                           f"성공률은 짧은 프롬프트 둘 다 30/30, 긴 프롬프트 REST {s_long['rest']['ok']}/30 · Spring AI 2 {s_long['spring-ai']['ok']}/30"
                           f"(Spring AI 2 쪽 20초 시간 초과 {30 - s_long['spring-ai']['ok']}건, 4절). "
                           "Spring AI 2는 Gemini 모델의 처리 속도를 바꾸는 기술이 아니므로 응답 시간이 비슷한 것은 예상된 결과다. "
                           "이번 전환의 실제 성과는 재시도 중첩 제거(한 요청 최대 15회 → 우리 코드 3회), 운영에서 발견한 HTTP/2 시간 초과의 해결, "
                           "호출 시간·토큰 지표 자동 수집이며, 이는 운영 기록(5절)으로 따로 보인다.", body))

    story.append(Paragraph("1. 측정 조건", h2))
    d = runs["short"][0]
    story.append(Paragraph(
        f"환경: {d['environment']} · JVM {d['jvm']} · 모델 {d['model']} · temperature 0.1 · JSON 응답 · "
        f"읽기/전체 제한 {d['timeoutMillis']}ms(둘 다) · 호출 사이 {d['pauseMillis']}ms 대기(무료 한도 15 RPM 준수) · "
        f"워밍업 1라운드 제외 · 라운드마다 먼저 부르는 클라이언트를 바꿈 · 앱·SDK·Spring AI 재시도 모두 끔(운영 설정 SpringAiConfig 그대로). "
        f"짧은 프롬프트 {runs['short'][0]['promptChars']}자, 긴 프롬프트 {runs['long'][0]['promptChars']}자(장소 24곳 설명, 입력 약 1,600토큰). "
        "REST = Spring RestClient(HttpURLConnection, HTTP/1.1) 직접 호출, Spring AI 2 = GoogleGenAiChatModel(google-genai SDK, OkHttp HTTP/1.1 고정). "
        "자원은 호출 한 번 동안의 프로세스 CPU 시간·호출 스레드 할당 메모리·GC 횟수를 JVM MXBean으로 쟀다. 하나의 JVM에서 두 클라이언트를 번갈아 부르므로 "
        "RSS는 나누어 볼 수 없고, 처리량(초당 요청)은 한도 때문에 직렬 호출이라 재지 않았다.", body))

    for kind, label in (("short", "짧은 프롬프트"), ("long", "긴 프롬프트")):
        data, by = runs[kind]
        if kind == "long":
            story.append(PageBreak())
        st = {c: stats(by[c]) for c in by}
        cpu = {c: stats(by[c], "cpuMillis") for c in by}
        alloc = {c: stats(by[c], "allocatedKiB") for c in by}
        gc = {c: sum(r["gcCount"] for r in by[c]) for c in by}
        tok = {c: statistics.fmean([r.get("promptTokens", 0) for r in by[c] if r.get("success")]) for c in by}
        story.append(Paragraph(f"2{'a' if kind == 'short' else 'b'}. {label} 결과 (클라이언트별 {data['samplesPerClient']}회)", h2))
        rows = [["지표", "REST", "Spring AI 2"]]
        rows.append(["성공 / 호출", f"{st['rest']['ok']} / {st['rest']['n']}", f"{st['spring-ai']['ok']} / {st['spring-ai']['n']}"])
        for k, name in (("p50", "응답 p50(ms)"), ("p95", "응답 p95(ms)"), ("p99", "응답 p99(ms)"), ("mean", "응답 평균(ms)"), ("min", "응답 최소(ms)"), ("max", "응답 최대(ms)")):
            rows.append([name, f"{st['rest'][k]:,.0f}", f"{st['spring-ai'][k]:,.0f}"])
        rows.append(["호출당 CPU 시간 중앙값(ms)", f"{cpu['rest']['p50']:.1f}", f"{cpu['spring-ai']['p50']:.1f}"])
        rows.append(["호출당 할당 메모리 중앙값(KiB)", f"{alloc['rest']['p50']:,.0f}", f"{alloc['spring-ai']['p50']:,.0f}"])
        rows.append(["GC 횟수 합(측정 구간)", f"{gc['rest']}", f"{gc['spring-ai']}"])
        rows.append(["입력 토큰 평균", f"{tok['rest']:,.0f}", f"{tok['spring-ai']:,.0f}"])
        story.append(table(rows, [60 * mm, 45 * mm, 45 * mm]))
        med, lo, hi, n = boot[kind]
        story.append(Paragraph(f"같은 라운드 짝 {n}개의 차이(Spring AI - REST) 중앙값 {med:+.0f}ms, bootstrap(seed 422·5,000회) 중앙 95% 범위 {lo:+.0f}~{hi:+.0f}ms. "
                               "p99는 표본 30개에서는 최대값에 가깝다.", small))
        png = tmp / f"chart-{kind}.png"
        chart(by, f"{label}: 호출별 분포(가로선 = 중앙값)", png, fp)
        story.append(img(png, 160 * mm))

    story.append(PageBreak())
    story.append(Paragraph("3. 재시도: 실제 Gemini 호출 횟수와 중복 호출", h2))
    story.append(Paragraph(
        "라이브러리 기본값 그대로면 google-genai SDK 5회 × Spring AI 10회 × 우리 코드 3회가 겹쳐 한 요청이 Gemini를 최대 15번(이론상 150번) 부를 수 있었고, "
        "2026-10-29 로컬 실측에서 20초 제한인데 한 호출이 41초 걸렸다(ADR 0020). 그래서 SDK 재시도 1회, Spring AI 재시도 0회로 고정하고 재시도는 "
        "GeminiJsonClientImpl 한 곳(최대 3회)에서만 한다(#245). 운영 기록(api_call_logs, 2026-11-01 17:00 KST #297 배포 이후~11-13)으로 확인한 결과:", body))
    story.append(table([
        ["운영 호출(Java, Spring AI 경로)", "호출", "성공", "평균 시도", "최대 시도", "2회 이상", "대기 있음"],
        ["trip-plan.draft(일정 초안)", "110", "108", "1.17", "3", "15", "15"],
        ["trip-plan.draft.repair", "2", "2", "1.00", "1", "0", "0"],
        ["trip-plan.draft.rule-repair", "1", "1", "1.00", "1", "0", "0"],
    ], [52 * mm, 18 * mm, 18 * mm, 20 * mm, 20 * mm, 20 * mm, 20 * mm]))
    story.append(Paragraph("시도 횟수가 3을 넘는 호출은 0건이다. 즉 라이브러리 쪽 숨은 재시도 없이 우리 코드의 상한(3회) 안에서만 다시 보낸다. "
                           "이 측정 도구에서는 전송 단위 시도 횟수를 따로 관찰하지 않았다(wireAttemptsObserved=false).", small))

    story.append(Paragraph("4. 안정성: 시간 초과와 오류", h2))
    fail_lines = []
    for kind, label in (("short", "짧은"), ("long", "긴")):
        data, by = runs[kind]
        for c, name in (("rest", "REST"), ("spring-ai", "Spring AI 2")):
            fails = [r for r in by[c] if not r.get("success")]
            desc = ", ".join(f"라운드 {r['round']} {'20초 시간 초과' if r.get('timeout') else r.get('errorType', '?')}"
                             + (f"(HTTP {r['httpStatus']})" if r.get("httpStatus") else "") for r in fails) or "없음"
            fail_lines.append(f"{label} 프롬프트 {name}: 실패 {len(fails)}/{len(by[c])} — {desc}")
        warm = [r for r in data["results"] if r["warmup"] and not r.get("success")]
        for r in warm:
            fail_lines.append(f"{label} 프롬프트 워밍업(집계 제외) {r['client']}: {r.get('errorType', '?')}"
                              + (f"(HTTP {r['httpStatus']})" if r.get("httpStatus") else "") + f", {r['milliseconds']:.0f}ms")
    story.append(Paragraph("<b>이번 로컬 측정에서:</b> " + " · ".join(fail_lines) + ". "
                           "시간 초과는 둘 다 20초 제한(REST 읽기 20초, Spring AI 전체 20초)에서 끊긴 것이고, 같은 라운드의 다른 클라이언트는 정상 응답했다. "
                           "Gemini 쪽 지연인지 클라이언트 쪽 문제인지 이 측정으로는 가릴 수 없다(전송 단위 관찰 없음). 표본 30회에서 2건 차이는 우연일 수 있어 "
                           "운영 지표(gen_ai_client_operation_seconds의 error 라벨)로 계속 본다.", body))
    story.append(Paragraph("<b>운영에서 본 것:</b>", body))
    story.append(Paragraph(
        "2026-10-31 01:41 KST Spring AI 2 배포 직후 운영에서만 Gemini 호출 6/6이 20초 시간 초과였다(합 120.8초, 같은 시각 맥에서 REST 직접 호출은 200·0.2초). "
        "원인은 google-genai가 OkHttp로 HTTP/2를 먼저 쓰는 것이었고, HTTP/1.1로 고정(#254, 02:19)한 뒤 같은 시나리오가 5.1초에 성공했다(토큰 926/325). "
        "이 사건의 Grafana 원본은 보관 기간이 지나 없고, 운영 서버 journal과 docs/bench/spring-ai-kafka-performance.md 기록이 근거다. "
        "그 뒤 11-08~11-13 운영 5일 동안 Spring AI 경로 호출은 성공 약 109회·실패 약 12회(실패는 11-12 밤에 몰림, 원인은 이번에 확인하지 않음), 최대 19.1초로 20초 제한 안에서 끝났다.", body))

    story.append(Paragraph("5. 운영 Grafana 캡처 (2026-11-09~11-13, Spring AI 2 적용 상태)", h2))
    for name, title in (("01-spring-ai-call-duration.png", "Gemini 호출 시간 — 1시간 평균과 최대(초), 성공/실패 구분 · gen_ai_client_operation_seconds"),
                        ("01b-spring-ai-call-count.png", "Gemini 호출 수(1시간) · 성공/실패"),
                        ("02-spring-ai-token-usage.png", "토큰 사용량(1시간) · 입력/출력/전체 · gen_ai_client_token_usage_total")):
        story.append(Paragraph(title, small)); story.append(img(cap / name)); story.append(Spacer(1, 4))
    story.append(PageBreak())
    story.append(Paragraph("운영 서버 자원 — 2026-11-12 15:00~17:30 KST(실제 사용 구간, 부하 실험 없음, 힙 상한 144MiB)", small))
    for name, title in (("10-prod-external-api-calls.png", "외부 API 호출 수(1시간) — Gemini·카카오·Google Places"),
                        ("11-prod-external-api-success.png", "외부 API 성공률 — Gemini 100%"),
                        ("12-prod-cpu.png", "CPU 사용률(서버 전체·java)"),
                        ("13-prod-heap.png", "JVM 힙 메모리(사용 중·committed·상한)"),
                        ("14-prod-gc.png", "GC 멈춤(가장 긴 멈춤·초당 멈춤 시간)")):
        story.append(Paragraph(title, small)); story.append(img(cap / name, 150 * mm)); story.append(Spacer(1, 3))

    story.append(PageBreak())
    story.append(Paragraph("6. 기존 측정값과의 비교", h2))
    story.append(table([
        ["측정", "조건", "REST", "Spring AI 2", "해석"],
        ["2026-10-31 로컬(#256)", "각 4회, 짧은 JSON", "중앙값 902ms", "892ms", "소표본 관측"],
        ["2026-11-01 로컬(#288)", "각 20회, 짧은 JSON, 번갈아", "중앙값 913ms", "719ms", "짝 차이 -136ms, 95% 범위 -311~+13ms → 입증 안 됨"],
        [f"2026-11-13 로컬(#422) 짧은", "각 30회, 번갈아, 자원 포함", f"{s_short['rest']['p50']:,.0f}ms", f"{s_short['spring-ai']['p50']:,.0f}ms", f"짝 차이 {boot['short'][0]:+.0f}ms, 95% {boot['short'][1]:+.0f}~{boot['short'][2]:+.0f}ms"],
        [f"2026-11-13 로컬(#422) 긴", "각 30회, 입력 약 1,600토큰", f"{s_long['rest']['p50']:,.0f}ms", f"{s_long['spring-ai']['p50']:,.0f}ms", f"짝 차이 {boot['long'][0]:+.0f}ms, 95% {boot['long'][1]:+.0f}~{boot['long'][2]:+.0f}ms"],
        ["운영 api_call_logs 초안 생성", "10-01~10-30 vs 10-31~11-07, 조건 다름", "13건 중앙값 2,103ms", "18건 3,366ms", "영상·프롬프트가 달라 비교 불가, Python 호출도 같이 흔들림"],
    ], [34 * mm, 38 * mm, 25 * mm, 25 * mm, 50 * mm]))

    story.append(Paragraph("7. 개선된 것과 아닌 것", h2))
    story.append(Paragraph(
        "<b>개선으로 말할 수 있는 것:</b> ① 재시도 중첩 제거 — 운영 110건에서 시도 3회 초과 0건, 하루 한도를 지키는 상한이 코드 한 곳에 있음. "
        "② 운영 HTTP/2 시간 초과(6/6)를 HTTP/1.1 고정으로 해결하고 그 뒤 같은 문제 재발 없음. "
        "③ 호출 시간·토큰·실패 지표(gen_ai_client_*)가 별도 코드 없이 Grafana에 쌓임. "
        "<b>개선으로 말할 수 없는 것:</b> 응답 속도 — 로컬 반복 측정에서 차이의 범위가 0을 포함하거나 수십 ms 수준이고, 운영 전후는 조건이 달라 비교 불가. "
        "JAR 크기 +24.7MB, RSS +16MB(로컬, #256)는 비용 쪽이다.", body))

    story.append(Paragraph("8. 한계", h2))
    story.append(Paragraph(
        "로컬 Mac에서 쟀으므로 운영(Oracle E2.1.Micro, 1/8 OCPU)의 절대값과 다르다. 두 클라이언트를 한 JVM에서 번갈아 불러 RSS는 분리하지 못했고, "
        "처리량은 무료 한도(15 RPM) 때문에 재지 않았다. 표본 30개에서 p99는 최대값과 거의 같다. 운영 p50/p95는 Grafana에 분포 지표가 없어 평균·최대만 있다. "
        "10-31 당시 Grafana 원본은 삭제돼 레포 기록과 서버 journal에 의존한다. 옛 REST 빌드를 운영에 다시 올려 같은 조건으로 재는 것은 사용자 영향 때문에 하지 않았다.", body))

    SimpleDocTemplate(args.output, pagesize=A4, leftMargin=16 * mm, rightMargin=16 * mm, topMargin=14 * mm, bottomMargin=14 * mm,
                      title="Trova Spring AI 2 재측정", author="Trova").build(story)
    print(args.output)


if __name__ == "__main__":
    main()
