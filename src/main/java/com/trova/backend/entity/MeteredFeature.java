package com.trova.backend.entity;

/**
 * 무료·여행 패스 한도로 세는 기능(#130). 분석·초안은 이미 있는 작업·초안 테이블에서 세고,
 * 비서·대안·전체 재구성(ASSIST)은 따로 남긴 사용 기록(UsageRecord)으로 센다.
 */
public enum MeteredFeature {
    ANALYSIS,
    DRAFT,
    ASSIST
}
