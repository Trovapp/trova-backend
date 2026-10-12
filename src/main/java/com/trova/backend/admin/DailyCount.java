package com.trova.backend.admin;

import java.time.LocalDate;

public record DailyCount(LocalDate date, long count) {
}
