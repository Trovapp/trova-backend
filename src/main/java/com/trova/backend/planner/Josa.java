package com.trova.backend.planner;

/**
 * 장소 이름 뒤 조사를 받침에 맞춰 고른다(디자인 QA W3). 예전엔 "만리향 만두은", "카페이 해 진 뒤"처럼 늘 받침 있는 쪽을 붙였다.
 * 끝 글자가 한글이 아니면(영문·숫자·기호) 받침을 알 수 없어 "은(는)"처럼 둘 다 적는다 — 앱의 objectParticle과 같은 규칙.
 */
public final class Josa {

    private Josa() {
    }

    private static Boolean hasFinalConsonant(String word) {
        if (word == null || word.isBlank()) {
            return null;
        }
        char last = word.strip().charAt(word.strip().length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return null;
        }
        return (last - 0xAC00) % 28 != 0;
    }

    private static String pick(String word, String withBatchim, String without) {
        Boolean b = hasFinalConsonant(word);
        return word + (b == null ? withBatchim + "(" + without + ")" : b ? withBatchim : without);
    }

    /** 만두는 / 국밥은 */
    public static String eunNeun(String word) {
        return pick(word, "은", "는");
    }

    /** 만두를 / 국밥을 */
    public static String eulReul(String word) {
        return pick(word, "을", "를");
    }

    /** 카페가 / 해변이 */
    public static String iGa(String word) {
        return pick(word, "이", "가");
    }
}
