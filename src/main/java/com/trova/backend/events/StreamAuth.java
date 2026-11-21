package com.trova.backend.events;

import jakarta.servlet.http.HttpSession;
import java.time.Instant;

record StreamAuth(Instant end, HttpSession session) {
    boolean validAt(Instant now) {
        if (!now.isBefore(end)) return false;
        if (session == null) return true;
        try {
            // The servlet contract rejects this read after logout, invalidation or timeout.
            session.getCreationTime();
            return true;
        } catch (IllegalStateException invalidated) {
            return false;
        }
    }
}
