package com.trova.backend.repository;

import java.time.LocalDate;

/** 여행 상세(#476)의 머리 — 멤버 조건으로 걸러 읽으므로 사용자 행을 따로 읽지 않는다. */
public record TripDetailHeader(Long id, String title, LocalDate startDate, LocalDate endDate, long version) {
}
