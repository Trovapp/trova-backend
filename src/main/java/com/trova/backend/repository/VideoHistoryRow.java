package com.trova.backend.repository;

import java.time.LocalDateTime;

public record VideoHistoryRow(Long jobId, String sourceUrl, LocalDateTime createdAt, long placeCount) {
}
