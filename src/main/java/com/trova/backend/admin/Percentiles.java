package com.trova.backend.admin;

import java.util.List;

public final class Percentiles {

    private Percentiles() {
    }

    /** nearest-rank 백분위(p는 0~1). 값이 없으면 null. */
    public static Long of(List<Long> values, double p) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(p * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }
}
