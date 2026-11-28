package com.trova.backend.controller;

import com.trova.backend.entity.Bookmark;
import com.trova.backend.entity.BookmarkFolder;
import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.PlaceCatalogUpdates;
import org.hibernate.Hibernate;
import org.hibernate.Interceptor;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.type.Type;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** flush에서 영향 범위를 모으고, 실제 DB 커밋이 성공한 뒤 SSE 알림보다 먼저 무효화한다. */
@Component
final class UserListWrites implements Interceptor, HibernatePropertiesCustomizer {
    private static final Set<String> BOOKMARK_FIELDS = Set.of("name", "googlePlaceId", "mood", "space",
            "latitude", "longitude", "category", "address");
    private final UserListReads reads;

    UserListWrites(UserListReads reads) {
        this.reads = reads;
    }

    @Override
    public void customize(Map<String, Object> settings) {
        settings.put(AvailableSettings.INTERCEPTOR, this);
    }

    @Override
    public boolean onPersist(Object entity, Object id, Object[] state, String[] names, Type[] types) {
        listRowChanged(entity);
        return false;
    }

    @Override
    public boolean onFlushDirty(Object entity, Object id, Object[] state, Object[] previous, String[] names, Type[] types) {
        if (entity instanceof Place place && changed(BOOKMARK_FIELDS, state, previous, names)) {
            changes().catalogPlaces.add(place.getId());
        } else if (entity instanceof User && changed(Set.of("nickname", "profileImageUrl"), state, previous, names)) {
            changes().allTrips = true;
        } else if (entity instanceof ProcessingJob job && changed(Set.of("sourceUrl"), state, previous, names)) {
            changes().places.add(job.getUser().getId());
        } else {
            listRowChanged(entity);
        }
        return false;
    }

    @Override
    public void onRemove(Object entity, Object id, Object[] state, String[] names, Type[] types) {
        if (entity instanceof Place place) changes().catalogPlaces.add(place.getId());
        else if (entity instanceof User user) {
            Changes pending = changes();
            pending.allTrips = true;
            pending.places.add(user.getId());
            pending.bookmarks.add(user.getId());
        } else if (entity instanceof ProcessingJob job) changes().places.add(job.getUser().getId());
        else if (entity instanceof BookmarkFolder folder) changes().bookmarks.add(folder.getUser().getId());
        else listRowChanged(entity);
    }

    private void listRowChanged(Object entity) {
        if (entity instanceof Bookmark bookmark) changes().bookmarks.add(bookmark.getUser().getId());
        else if (entity instanceof SavedPlace place) changes().places.add(place.getUser().getId());
        else if (entity instanceof Trip trip) tripChanged(trip);
        else if (entity instanceof Itinerary day) tripChanged(day.getTrip());
        else if (entity instanceof TripPlace stop) {
            Itinerary day = stop.getItinerary();
            if (Hibernate.isInitialized(day)) tripChanged(day.getTrip());
            else changes().allTrips = true;
        }
    }

    private void tripChanged(Trip trip) {
        Changes pending = changes();
        if (Hibernate.isInitialized(trip) && Hibernate.isInitialized(trip.getMembers())) {
            trip.getMembers().forEach(member -> pending.trips.add(member.getUser().getId()));
        } else {
            // flush 중 추가 SQL 없이, 아직 알 수 없는 공유 멤버까지 보호한다.
            pending.allTrips = true;
        }
    }

    @Override
    public void onCollectionUpdate(Object collection, Object key) {
        membershipChanged(collection);
    }

    @Override
    public void onCollectionRemove(Object collection, Object key) {
        membershipChanged(collection);
    }

    private void membershipChanged(Object collection) {
        if (collection instanceof PersistentCollection<?> members && members.getOwner() instanceof Trip) {
            // 제거된 멤버는 현재 컬렉션에 없다.
            changes().allTrips = true;
        }
    }

    @EventListener
    public void catalogChanged(PlaceCatalogUpdates.BookmarkFieldsChanged event) {
        changes().catalogPlaces.add(event.placeId());
    }

    private static boolean changed(Set<String> fields, Object[] state, Object[] previous, String[] names) {
        if (previous == null) return true;
        for (int i = 0; i < names.length; i++) {
            if (fields.contains(names[i]) && !Objects.equals(previous[i], state[i])) return true;
        }
        return false;
    }

    private Changes changes() {
        if (TransactionSynchronizationManager.getResource(this) instanceof Changes existing) return existing;
        Changes pending = new Changes();
        TransactionSynchronizationManager.bindSynchronizedResource(this, pending);
        TransactionSynchronizationManager.registerSynchronization(pending);
        return pending;
    }

    private final class Changes implements TransactionSynchronization {
        private final Set<Long> places = new HashSet<>();
        private final Set<Long> bookmarks = new HashSet<>();
        private final Set<Long> trips = new HashSet<>();
        private final Set<Long> catalogPlaces = new HashSet<>();
        private boolean allTrips;

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public void afterCommit() {
            reads.invalidatePlaces(places);
            reads.invalidateBookmarks(bookmarks, catalogPlaces);
            reads.invalidateTrips(trips, allTrips);
        }
    }
}
