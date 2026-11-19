package com.trova.backend.repository;

/** 같은 영상인지 비교하는 데 필요한 작업 번호와 원래 주소(초안 상세의 현재 번호 계산용, #473). */
public record JobSourceUrlRow(Long id, String sourceUrl) {
}
