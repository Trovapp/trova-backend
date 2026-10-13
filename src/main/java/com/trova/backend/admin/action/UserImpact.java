package com.trova.backend.admin.action;

import com.trova.backend.entity.User;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/** 계정 하나에 딸린 데이터 건수 — 계정 삭제 미리보기·결과에 쓴다. */
@Component
public class UserImpact {

    public record Counts(long jobs, long savedPlaces, long trips, long drafts) {
    }

    private final EntityManager entityManager;

    public UserImpact(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Counts count(User user) {
        return new Counts(
                count("select count(j) from ProcessingJob j where j.user = :u", user),
                count("select count(p) from SavedPlace p where p.user = :u", user),
                count("select count(t) from Trip t where t.user = :u", user),
                count("select count(d) from TripDraft d where d.user = :u", user));
    }

    private long count(String jpql, User user) {
        return entityManager.createQuery(jpql, Long.class).setParameter("u", user).getSingleResult();
    }
}
