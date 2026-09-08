package com.trova.backend.congestion;

import java.util.Optional;

public interface SeoulCongestionApiClient {
    Optional<SeoulCongestionResponse> fetchCongestion(String areaName);
}
