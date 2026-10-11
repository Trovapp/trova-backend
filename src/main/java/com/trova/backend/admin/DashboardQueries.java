package com.trova.backend.admin;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 대시보드 일별 건수 쿼리. 엔티티 이름은 아래 enum의 고정 값만 쓰므로 사용자 입력이 쿼리에 들어가지 않는다.
 * 날짜별 묶기는 DB가 한다(응답·메모리가 행 수와 상관없이 일정).
 */
@Repository
public class DashboardQueries {

    public enum CountedEntity {
        USERS("User", ""),
        SAVED_PLACES("SavedPlace", ""),
        TRIPS("Trip", ""),
        AUTO_DRAFTS("TripDraft", " and e.autoCreated = true");

        private final String entityName;
        private final String extraWhere;

        CountedEntity(String entityName, String extraWhere) {
            this.entityName = entityName;
            this.extraWhere = extraWhere;
        }
    }

    private final EntityManager entityManager;

    public DashboardQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public List<DailyCount> dailyCounts(CountedEntity entity, LocalDateTime since) {
        String jpql = "select new com.trova.backend.admin.DailyCount(cast(e.createdAt as LocalDate), count(e)) from "
                + entity.entityName + " e where e.createdAt >= :since" + entity.extraWhere
                + " group by cast(e.createdAt as LocalDate)";
        return entityManager.createQuery(jpql, DailyCount.class).setParameter("since", since).getResultList();
    }

    public long countAll(CountedEntity entity) {
        return entityManager.createQuery("select count(e) from " + entity.entityName + " e", Long.class).getSingleResult();
    }
}
