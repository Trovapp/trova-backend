package com.trova.backend.repository;

import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 벌크 UPDATE는 엔티티 flush 콜백을 거치지 않으므로 영향받은 행이 있을 때 같은 트랜잭션에서 알린다. */
@Transactional
public class PlaceCatalogUpdatesImpl implements PlaceCatalogUpdates {
    private final EntityManager em;
    private final ApplicationEventPublisher events;

    public PlaceCatalogUpdatesImpl(EntityManager em, ApplicationEventPublisher events) {
        this.em = em;
        this.events = events;
    }

    @Override
    public int updateSpaceTag(Long id, String space, LocalDateTime taggedAt) {
        int updated = em.createQuery("update Place p set p.space=:space, p.spaceTaggedAt=:taggedAt where p.id=:id")
                .setParameter("space", space).setParameter("taggedAt", taggedAt).setParameter("id", id).executeUpdate();
        return publishIfChanged(id, updated);
    }

    @Override
    public int repairMissingLocation(Long id, double latitude, double longitude, String address) {
        int updated = em.createQuery("""
                update Place p set p.latitude=:latitude, p.longitude=:longitude, p.address=coalesce(p.address,:address)
                where p.id=:id and (p.latitude is null or p.longitude is null)
                """).setParameter("id", id).setParameter("latitude", latitude).setParameter("longitude", longitude)
                .setParameter("address", address).executeUpdate();
        return publishIfChanged(id, updated);
    }

    private int publishIfChanged(Long id, int updated) {
        if (updated > 0) events.publishEvent(new BookmarkFieldsChanged(id));
        return updated;
    }
}
