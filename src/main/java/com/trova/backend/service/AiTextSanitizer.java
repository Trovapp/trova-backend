package com.trova.backend.service;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI가 만든 문장에서 "AI가 쓴 글"처럼 보이는 기호를 없앤다(#53) — 대시, 글머리 기호, 문장 중간 기호, 따옴표.
 * 지시문으로도 막지만 모델이 가끔 어기므로 사용자에게 보내기 직전에 한 번 더 정리한다.
 * 의미가 있는 하이픈(K-POP, 전화번호)과 리뷰 하이라이트의 **강조** 표시(앱이 굵게 보여줌)는 건드리지 않는다.
 */
public final class AiTextSanitizer {

    // 줄 맨 앞의 글머리: "- ", "• ", "1. ", "2) " — "**강조**"처럼 별표 뒤에 공백이 없는 건 글머리가 아니다.
    private static final Pattern LIST_MARKER = Pattern.compile("^\\s*(?:[-•·▪◦‣*]|\\d{1,2}[.)])\\s+");
    // 숫자 사이의 긴 대시는 범위를 뜻하므로 물결로 바꾼다(10:00–18:00 → 10:00~18:00).
    private static final Pattern NUMBER_RANGE_DASH = Pattern.compile("(\\d)\\s*[–—]\\s*(\\d)");
    // 문장 중간의 긴 대시와 띄어 쓴 하이픈은 쉼표로 이어 준다. 붙여 쓴 하이픈(K-POP, 051-123)은 대상이 아니다.
    private static final Pattern SENTENCE_DASH = Pattern.compile("\\s*[–—]+\\s*|\\s+-\\s+");
    private static final Pattern QUOTES = Pattern.compile("[\"“”„‟«»「」『』‘’]");
    // 따옴표로 쓰인 작은따옴표만 지운다('인생샷'). 단어 속 아포스트로피는 한국어 문장에선 거의 없지만 남긴다.
    private static final Pattern SINGLE_QUOTED = Pattern.compile("'([^'\\n]{1,40})'");
    private static final Pattern SYMBOLS = Pattern.compile("[※•▶►▷→⇒➡★☆✔✓]");

    private AiTextSanitizer() {
    }

    public static String clean(String text) {
        if (text == null) {
            return null;
        }
        return Arrays.stream(text.split("\n", -1))
                .map(AiTextSanitizer::cleanLine)
                .collect(Collectors.joining("\n"))
                .strip();
    }

    private static String cleanLine(String line) {
        String result = LIST_MARKER.matcher(line).replaceFirst("");
        result = NUMBER_RANGE_DASH.matcher(result).replaceAll("$1~$2");
        result = SENTENCE_DASH.matcher(result).replaceAll(", ");
        result = SINGLE_QUOTED.matcher(result).replaceAll("$1");
        result = QUOTES.matcher(result).replaceAll("");
        result = SYMBOLS.matcher(result).replaceAll(" ");
        // 기호를 지우고 남은 자국 정리: 겹친 공백, 쉼표 앞 공백, 겹친 쉼표, 앞뒤에 남은 쉼표.
        result = result.replaceAll("[ \\t]{2,}", " ")
                .replaceAll("\\s+,", ",")
                .replaceAll(",(\\s*,)+", ",")
                .replaceAll("^\\s*,\\s*", "")
                .replaceAll(",\\s*$", "");
        return result.strip();
    }
}
