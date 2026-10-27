package com.trova.backend.admin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 사용자 퍼널·기능 사용 쿼리(#237). 엔티티 이름과 조건은 아래 enum의 고정 값만 쓰므로 사용자 입력이 쿼리에 들어가지 않는다.
 * 관리자 계정은 "로그인제공자:제공자사용자ID"(AdminAccessService와 같은 키)로 뺀다.
 */
@Repository
public class FunnelQueries {

    private static final String SIGNAL = "com.trova.backend.entity.SignalType.";

    public enum Activity {
        SHARES("ProcessingJob", ""),
        SHARES_DONE("ProcessingJob", " and e.status = com.trova.backend.entity.JobStatus.DONE"),
        SAVED_PLACES("SavedPlace", ""),
        BOOKMARKS("Bookmark", ""),
        DRAFTS("TripDraft", ""),
        DRAFTS_APPROVED("TripDraft", " and e.status = com.trova.backend.entity.TripDraftStatus.APPROVED"),
        DRAFTS_AUTO("TripDraft", " and e.autoCreated = true"),
        TRIPS("Trip", ""),
        ALTERNATIVE_REPLACED("UserPreferenceSignal", " and e.signalType = " + SIGNAL + "ALTERNATIVE_REPLACED"),
        GAP_INSERTED("UserPreferenceSignal", " and e.signalType = " + SIGNAL + "GAP_INSERTED"),
        CHAT_LIKED("UserPreferenceSignal", " and e.signalType = " + SIGNAL + "CHAT_LIKED"),
        // 여행 다듬기 = 대안 교체·빈 시간 채우기·비서 후보 좋아요 중 하나.
        REFINES("UserPreferenceSignal", " and e.signalType in (" + SIGNAL + "ALTERNATIVE_REPLACED, "
                + SIGNAL + "GAP_INSERTED, " + SIGNAL + "CHAT_LIKED)"),
        WEATHER_REPLANS("TripReplanJob", "");

        private final String entityName;
        private final String extraWhere;

        Activity(String entityName, String extraWhere) {
            this.entityName = entityName;
            this.extraWhere = extraWhere;
        }
    }

    public record CohortUser(Long id, String nickname, LocalDateTime createdAt) {
    }

    public record Usage(long count, long users) {
    }

    public record PerUser(long count, LocalDateTime first, LocalDateTime last) {
    }

    private static final String NOT_ADMIN = " and concat(%s.provider, ':', %s.providerUserId) not in :adminKeys";

    private final EntityManager entityManager;

    public FunnelQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** 기간 안에 가입한 관리자 아닌 사용자. since가 null이면 전체. */
    public List<CohortUser> cohort(LocalDateTime since, Set<String> adminKeys) {
        String jpql = "select new com.trova.backend.admin.FunnelQueries$CohortUser(u.id, u.nickname, u.createdAt)"
                + " from User u where 1 = 1" + (since == null ? "" : " and u.createdAt >= :since")
                + String.format(NOT_ADMIN, "u", "u");
        TypedQuery<CohortUser> query = entityManager.createQuery(jpql, CohortUser.class)
                .setParameter("adminKeys", nonEmpty(adminKeys));
        if (since != null) {
            query.setParameter("since", since);
        }
        return query.getResultList();
    }

    /** 기간 안 건수와 쓴 사람 수(관리자 제외). since가 null이면 전체. */
    public Usage usage(Activity activity, LocalDateTime since, Set<String> adminKeys) {
        String jpql = "select count(e), count(distinct e.user.id) from " + activity.entityName + " e where 1 = 1"
                + (since == null ? "" : " and e.createdAt >= :since") + activity.extraWhere
                + String.format(NOT_ADMIN, "e.user", "e.user");
        TypedQuery<Object[]> query = entityManager.createQuery(jpql, Object[].class)
                .setParameter("adminKeys", nonEmpty(adminKeys));
        if (since != null) {
            query.setParameter("since", since);
        }
        Object[] row = query.getSingleResult();
        return new Usage(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
    }

    /** 주어진 사용자마다 건수·처음·마지막 시각(기간과 상관없이 지금까지). 기록이 없는 사용자는 결과에 없다. */
    public Map<Long, PerUser> perUser(Activity activity, Collection<Long> userIds) {
        Map<Long, PerUser> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        String jpql = "select e.user.id, count(e), min(e.createdAt), max(e.createdAt) from " + activity.entityName
                + " e where e.user.id in :ids" + activity.extraWhere + " group by e.user.id";
        List<Object[]> rows = entityManager.createQuery(jpql, Object[].class).setParameter("ids", userIds).getResultList();
        for (Object[] row : rows) {
            result.put((Long) row[0], new PerUser(((Number) row[1]).longValue(), (LocalDateTime) row[2], (LocalDateTime) row[3]));
        }
        return result;
    }

    // 빈 목록은 "not in ()"이 되어 쿼리가 깨진다. ':'가 없는 값은 어떤 사용자 키와도 같지 않다.
    private static Set<String> nonEmpty(Set<String> adminKeys) {
        if (!adminKeys.isEmpty()) {
            return adminKeys;
        }
        Set<String> placeholder = new HashSet<>();
        placeholder.add("");
        return placeholder;
    }
}
