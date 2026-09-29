package com.trova.backend.pipeline;

import java.util.List;

/**
 * 파이프라인이 끝나기 전에 stderr로 알려주는 중간 결과(#51). 한 줄에 둘 중 하나만 담긴다.
 * 앱이 분석 대기 화면에서 "무엇을 보고, 무엇을 찾았는지"를 보여주는 데 쓴다.
 */
public record PipelineProgress(String title, List<String> placeNames) {
}
