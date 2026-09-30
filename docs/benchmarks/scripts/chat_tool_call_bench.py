"""AI 대화 지시문이 Gemini의 장소 찾기 도구 호출을 제대로 이끄는지 측정한다(#69).

서버가 보내는 요청과 같은 형태(지시문 + 도구 선언, 호출 모드 AUTO, 세션 첫 메시지)로 Gemini를 직접 호출한다.
지시문은 Java 소스(GeminiChatClientImpl.SYSTEM_INSTRUCTION)에서 그대로 읽어, 커밋된 문장을 측정한다.

사용법: GEMINI_API_KEY=... python chat_tool_call_bench.py <GeminiChatClientImpl.java> <라벨> <결과.json> [반복수]
"""
import json, os, re, sys, time, urllib.request

# "찾아드릴까요" 같은 제안은 괜찮고, 도구 없이 "이미 찾았다/카드로 보여준다"고 말하는 것만 잡는다.
CARD_WORDS = re.compile(r"카드|화면|찾아드렸|찾아두었|찾아뒀|찾아보았|찾아봤|골라보았|골라봤|띄워")

NOTE = {"name": "note_preference", "description": "사용자가 방금 대화에서 실제로 보여준 특정 후보 장소를 마음에 들어한다고 말하면 호출한다.",
        "parameters": {"type": "object", "properties": {"placeId": {"type": "integer", "description": "마음에 들어한 장소의 placeId"}}}}
PLACE_TOOLS = [{"functionDeclarations": [
    {"name": "find_alternatives", "description": "지금 보고 있는 여행 장소 근처의 대안 후보를 찾는다. 카테고리나 실내 여부로 필터링할 수 있다.",
     "parameters": {"type": "object", "properties": {
         "category": {"type": "string", "description": "찾고 싶은 카테고리, 예: 카페"},
         "indoor": {"type": "boolean", "description": "실내 장소만 찾을지 여부"}}}}, NOTE]}]
GAP_TOOLS = [{"functionDeclarations": [
    {"name": "get_gap_recommendations", "description": "일정 중 비어있는 시간에 갈 만한 근처 장소를 찾는다. 인자가 필요 없다.",
     "parameters": {"type": "object", "properties": {}}}, NOTE]}]

# (세션 종류, 기대, 메시지). 기대 "찾기"면 그 세션의 찾기 도구를 불러야 하고, "일반"이면 찾기 도구를 부르지 않아야 한다.
CASES = [("장소", "찾기", m) for m in ["근처 카페 추천해줘", "점심 먹을 곳 알려줘", "비 오면 갈 만한 실내 장소 있어?", "아이랑 가기 좋은 곳",
                                     "저녁에 산책할 곳", "여기 말고 다른 데 없어?", "조용한 데 가고 싶어", "사진 찍기 좋은 곳 있을까"]] + \
        [("빈시간", "찾기", m) for m in ["이 시간에 뭐 하면 좋을까", "빈 시간에 들를 만한 곳 추천해줘", "한 시간 정도 비는데 갈 데 있어?", "근처에 잠깐 쉴 곳"]] + \
        [("장소", "일반", m) for m in ["고마워", "여기 몇 시에 문 열어?", "안녕", "날씨 어때?", "너는 누구야?", "이 일정 괜찮은 것 같아?"]]


def read_instruction(java_path):
    src = open(java_path, encoding="utf-8").read()
    block = src.split("static final String SYSTEM_INSTRUCTION =", 1)[1].split(";", 1)[0]
    block = "\n".join(l for l in block.splitlines() if not l.strip().startswith("//"))
    pieces = re.findall(r'"((?:[^"\\]|\\.)*)"', block)
    return "".join(pieces).replace('\\n', '\n').replace('\\"', '"')


def call(key, instruction, tools, message):
    body = {"systemInstruction": {"parts": [{"text": instruction}]},
            "contents": [{"role": "user", "parts": [{"text": message}]}], "tools": tools}
    req = urllib.request.Request(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent",
        data=json.dumps(body).encode(), headers={"Content-Type": "application/json", "x-goog-api-key": key})
    with urllib.request.urlopen(req, timeout=60) as r:
        parts = json.loads(r.read())["candidates"][0]["content"].get("parts", [])
    fc = next((p["functionCall"] for p in parts if "functionCall" in p), None)
    text = "".join(p.get("text", "") for p in parts if "text" in p)
    return fc, text


def main():
    java, label, out = sys.argv[1], sys.argv[2], sys.argv[3]
    rounds = int(sys.argv[4]) if len(sys.argv) > 4 else 3
    key = os.environ["GEMINI_API_KEY"]
    instruction = read_instruction(java)
    rows = []
    for rnd in range(1, rounds + 1):
        for session, expect, msg in CASES:
            search_tool = "find_alternatives" if session == "장소" else "get_gap_recommendations"
            t = time.time()
            try:
                fc, text = call(key, instruction, PLACE_TOOLS if session == "장소" else GAP_TOOLS, msg)
                err = None
            except Exception as e:  # 503 같은 일시 오류는 따로 센다
                fc, text, err = None, "", str(e)[:80]
            called = fc["name"] if fc else None
            ok = None if err else ((called == search_tool) if expect == "찾기" else (called != search_tool and not CARD_WORDS.search(text)))
            fake_card = bool(not err and called is None and CARD_WORDS.search(text))
            rows.append({"round": rnd, "session": session, "expect": expect, "message": msg, "latency_s": round(time.time() - t, 2),
                         "tool": called, "args": fc.get("args") if fc else None, "text": text[:120], "ok": ok, "fake_card": fake_card, "error": err})
            r = rows[-1]
            print(f"[{label}] {rnd}회 {session:3} {expect} {r['latency_s']:5.1f}초 {'오류' if err else ('통과' if ok else '실패')} {msg} → {called or text[:40]}", flush=True)
            time.sleep(4.2)  # 무료 티어 분당 15회 제한
    json.dump({"label": label, "instruction": instruction, "rows": rows}, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
