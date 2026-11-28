package com.trova.backend.shipandping;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
record RequestRecord(String type, String route, String method, int status, long duration_ms,
                     String ts, String error_kind, String error_message, Long queue_ms, long timeout_ms,
                     Long db_ms, Long external_ms) {}
