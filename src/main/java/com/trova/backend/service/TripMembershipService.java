package com.trova.backend.service;

import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripMember;
import com.trova.backend.entity.User;
import com.trova.backend.repository.TripRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TripMembershipService {
    private final TripChanges changes;
    private final TripRepository trips;
    private final TripShareLinkService links;

    public TripMembershipService(TripRepository trips, TripShareLinkService links, TripChanges changes) {
        this.changes = changes;
        this.trips = trips;
        this.links = links;
    }

    public record Member(Long userId, String nickname, String profileImageUrl, String role) {}
    public static List<Member> members(Trip trip) {
        return trip.getMembers().stream().map(m -> new Member(m.getUser().getId(),
                m.getUser().getNickname(), m.getUser().getProfileImageUrl(), m.getRole().name())).toList();
    }

    @Transactional(readOnly = true)
    public List<Member> list(Long id, User user) {
        Trip trip = trips.findById(id).orElseThrow(TripMembershipService::missing);
        TripAccess.requireMember(trip, user);
        return members(trip);
    }

    @Transactional
    public Long join(String token, User user) {
        Long id = links.lockTripForToken(token).orElseThrow(TripMembershipService::missing).getId();
        Trip trip = links.findPublic(token).orElseThrow(TripMembershipService::missing);
        if (trip.getMembers().stream().anyMatch(m -> m.getUser().getId().equals(user.getId()))) return id;
        if (trip.getMembers().size() >= 10) throw new MemberLimitException();
        changes.record(trip, user, "MEMBER_JOINED", "여행에 참여했어요");
        trip.getMembers().add(new TripMember(user, TripMember.Role.EDITOR));
        return id;
    }

    public static class MemberLimitException extends RuntimeException {}

    @Transactional
    public void remove(Long id, Long userId, User actor) {
        Trip trip = trips.lockById(id).orElseThrow(TripMembershipService::missing);
        TripAccess.requireMember(trip, actor);
        if (!actor.getId().equals(userId)) TripAccess.requireOwner(trip, actor);
        if (trip.getUser().getId().equals(userId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (!trip.getMembers().removeIf(m -> m.getUser().getId().equals(userId))) throw missing();
        changes.record(trip, actor, "MEMBER_REMOVED", "여행 멤버가 변경됐어요");
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
