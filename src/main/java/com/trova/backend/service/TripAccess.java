package com.trova.backend.service;

import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class TripAccess {
    private TripAccess() {}

    public static boolean requireMember(Trip trip, User user) {
        if (trip.getUser().getId().equals(user.getId()) || trip.getMembers().stream()
                .anyMatch(member -> member.getUser().getId().equals(user.getId()))) {
            return true;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    public static boolean requireOwner(Trip trip, User user) {
        if (trip.getUser().getId().equals(user.getId())) return true;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
