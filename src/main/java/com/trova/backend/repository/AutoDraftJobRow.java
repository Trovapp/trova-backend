package com.trova.backend.repository;

import com.trova.backend.entity.JobStatus;

/** 같은 영상의 최신 완료 분석을 고르는 데 필요한 값. */
public record AutoDraftJobRow(Long id, String sourceUrl, String title, JobStatus status) {
}
