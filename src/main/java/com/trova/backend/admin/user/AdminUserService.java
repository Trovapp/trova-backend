package com.trova.backend.admin.user;

import com.trova.backend.admin.FailureReason;
import com.trova.backend.admin.action.AdminActionException;
import com.trova.backend.admin.action.RetryJobAction;
import com.trova.backend.admin.action.UserImpact;
import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.entity.User;
import com.trova.backend.service.AdminAccessService;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 관리자 사용자 찾기·상세(#167, ADR 0014). 읽기만 하고, 닉네임은 가리고, 로그인 고유번호·프로필 사진은 내보내지 않는다.
 * 검색어는 어디에도 남기지 않는다(로그 포함).
 */
@Service
public class AdminUserService {

    static final int SEARCH_LIMIT = 20;
    private static final int RECENT_JOBS = 10;
    private static final int TRIPS = 20;
    private static final int AUTO_DRAFTS = 10;
    private static final char LIKE_ESCAPE = '\\';

    public record UserSummary(Long id, String provider, String nickname, LocalDateTime createdAt,
                              boolean suspended, long jobs, long trips) {
    }

    public record JobItem(Long id, JobStatus status, FailureReason failureReason, LocalDateTime createdAt,
                          boolean retryable) {
    }

    public record TripItem(Long id, String title, LocalDate startDate, LocalDate endDate) {
    }

    public record DraftItem(Long id, TripDraftStatus status, boolean dismissed, LocalDateTime createdAt) {
    }

    public record UserDetail(Long id, String provider, String nickname, LocalDateTime createdAt,
                             LocalDateTime lastActiveAt, LocalDateTime suspendedAt, boolean admin,
                             UserImpact.Counts counts, List<JobItem> recentJobs, List<TripItem> trips,
                             List<DraftItem> autoDrafts) {
    }

    private final EntityManager entityManager;
    private final UserImpact userImpact;
    private final AdminAccessService adminAccessService;

    public AdminUserService(EntityManager entityManager, UserImpact userImpact,
                            AdminAccessService adminAccessService) {
        this.entityManager = entityManager;
        this.userImpact = userImpact;
        this.adminAccessService = adminAccessService;
    }

    /** 숫자만 → 내부 번호, http로 시작 → 그 링크를 제출한 사용자, 그 밖 → 닉네임·여행 이름 일부. 비면 최근 가입 20명. */
    @Transactional(readOnly = true)
    public List<UserSummary> search(String q) {
        String query = q == null ? "" : q.strip();
        List<User> users;
        if (query.isEmpty()) {
            users = entityManager.createQuery("select u from User u order by u.id desc", User.class)
                    .setMaxResults(SEARCH_LIMIT).getResultList();
        } else if (query.chars().allMatch(c -> c >= '0' && c <= '9')) {
            users = byId(query);
        } else if (query.startsWith("http")) {
            users = entityManager.createQuery(
                            "select distinct u from ProcessingJob j join j.user u where j.sourceUrl = :q order by u.id",
                            User.class)
                    .setParameter("q", query).setMaxResults(SEARCH_LIMIT).getResultList();
        } else {
            users = byNicknameOrTripTitle(query);
        }
        return summaries(users);
    }

    @Transactional(readOnly = true)
    public UserDetail detail(Long id) {
        User user = entityManager.find(User.class, id);
        if (user == null) {
            throw AdminActionException.notFound();
        }
        List<JobItem> recentJobs = entityManager.createQuery(
                        "select j from ProcessingJob j where j.user = :u order by j.id desc", ProcessingJob.class)
                .setParameter("u", user).setMaxResults(RECENT_JOBS).getResultList().stream()
                .map(j -> new JobItem(j.getId(), j.getStatus(),
                        j.getStatus() == JobStatus.FAILED ? FailureReason.classify(j.getErrorMessage()) : null,
                        j.getCreatedAt(), RetryJobAction.isRetryableFailure(j)))
                .toList();
        List<TripItem> trips = entityManager.createQuery(
                        "select t from Trip t where t.user = :u order by t.id desc", Trip.class)
                .setParameter("u", user).setMaxResults(TRIPS).getResultList().stream()
                .map(t -> new TripItem(t.getId(), t.getTitle(), t.getStartDate(), t.getEndDate()))
                .toList();
        List<DraftItem> autoDrafts = entityManager.createQuery(
                        "select d from TripDraft d where d.user = :u and d.autoCreated = true order by d.id desc",
                        TripDraft.class)
                .setParameter("u", user).setMaxResults(AUTO_DRAFTS).getResultList().stream()
                .map(d -> new DraftItem(d.getId(), d.getStatus(), d.getDismissedAt() != null, d.getCreatedAt()))
                .toList();
        return new UserDetail(user.getId(), user.getProvider(), NicknameMask.mask(user.getNickname()),
                user.getCreatedAt(), lastActiveAt(user), user.getSuspendedAt(), adminAccessService.isAdmin(user),
                userImpact.count(user), recentJobs, trips, autoDrafts);
    }

    private List<User> byId(String digits) {
        long id;
        try {
            id = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return List.of();
        }
        User user = entityManager.find(User.class, id);
        return user == null ? List.of() : List.of(user);
    }

    private List<User> byNicknameOrTripTitle(String query) {
        String pattern = "%" + escapeLike(query.toLowerCase(Locale.ROOT)) + "%";
        Map<Long, User> merged = new TreeMap<>();
        entityManager.createQuery(
                        "select u from User u where lower(u.nickname) like :q escape '\\' order by u.id", User.class)
                .setParameter("q", pattern).setMaxResults(SEARCH_LIMIT).getResultList()
                .forEach(u -> merged.put(u.getId(), u));
        entityManager.createQuery(
                        "select distinct u from Trip t join t.user u where lower(t.title) like :q escape '\\' order by u.id",
                        User.class)
                .setParameter("q", pattern).setMaxResults(SEARCH_LIMIT).getResultList()
                .forEach(u -> merged.put(u.getId(), u));
        return merged.values().stream().limit(SEARCH_LIMIT).toList();
    }

    static String escapeLike(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c == '%' || c == '_' || c == LIKE_ESCAPE) {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    private List<UserSummary> summaries(List<User> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        List<Long> ids = users.stream().map(User::getId).toList();
        Map<Long, Long> jobs = countByUser("select j.user.id, count(j) from ProcessingJob j "
                + "where j.user.id in :ids group by j.user.id", ids);
        Map<Long, Long> trips = countByUser("select t.user.id, count(t) from Trip t "
                + "where t.user.id in :ids group by t.user.id", ids);
        return users.stream()
                .map(u -> new UserSummary(u.getId(), u.getProvider(), NicknameMask.mask(u.getNickname()),
                        u.getCreatedAt(), u.isSuspended(), jobs.getOrDefault(u.getId(), 0L),
                        trips.getOrDefault(u.getId(), 0L)))
                .toList();
    }

    private Map<Long, Long> countByUser(String jpql, Collection<Long> ids) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : entityManager.createQuery(jpql, Object[].class).setParameter("ids", ids).getResultList()) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    /** 그 사용자 작업·여행 중 가장 최근에 바뀐 시각. 없으면 가입일. */
    private LocalDateTime lastActiveAt(User user) {
        LocalDateTime latest = user.getCreatedAt();
        for (String jpql : List.of("select max(j.updatedAt) from ProcessingJob j where j.user = :u",
                "select max(t.updatedAt) from Trip t where t.user = :u")) {
            LocalDateTime candidate = entityManager.createQuery(jpql, LocalDateTime.class)
                    .setParameter("u", user).getSingleResult();
            if (candidate != null && candidate.isAfter(latest)) {
                latest = candidate;
            }
        }
        return latest;
    }
}
