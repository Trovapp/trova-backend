package com.trova.backend.repository;

import java.time.LocalDateTime;

public record VideoHistoryRow(Long jobId, LocalDateTime createdAt, long placeCount) {
}
