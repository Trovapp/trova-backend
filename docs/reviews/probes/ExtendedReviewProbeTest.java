package com.trova.backend.reviews;

import com.trova.backend.admin.action.ActionPreview;
import com.trova.backend.admin.action.AdminAction;
import com.trova.backend.admin.action.AdminActionHandler;
import com.trova.backend.admin.action.AdminActionService;
import com.trova.backend.admin.action.AdminAuditLog;
import com.trova.backend.admin.action.ConfirmTokenStore;
import com.trova.backend.controller.TripReplanController;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.entity.User;
import com.trova.backend.pipeline.ReviewSummaryRunner;
import com.trova.backend.push.HttpExpoPushClient;
import com.trova.backend.recommendation.GooglePlacesApiClient;
import com.trova.backend.recommendation.GooglePlacesDetailsResponse;
import com.trova.backend.recommendation.PlaceReviewService;
import com.trova.backend.repository.AdminActionLogRepository;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.TripReplanJobRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.service.ApiCallLogService;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.DailyQuotaService;
import com.trova.backend.service.TripReplanJobService;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Review witnesses of current behavior, not desired regression contracts. External calls are mocked. */
class ExtendedReviewProbeTest {
    private record ReplanFixture(User user, TripReplanController controller,
                                 TripReplanJobRepository jobs, TripReplanJobService worker) { }

    private ReplanFixture replanFixture() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        Trip trip = mock(Trip.class);
        when(trip.getId()).thenReturn(9L);
        when(trip.getUser()).thenReturn(user);
        CurrentUserService users = mock(CurrentUserService.class);
        when(users.resolve(any())).thenReturn(user);
        TripRepository trips = mock(TripRepository.class);
        when(trips.findById(9L)).thenReturn(Optional.of(trip));
        TripReplanJobRepository jobs = mock(TripReplanJobRepository.class);
        AtomicLong ids = new AtomicLong();
        when(jobs.save(any())).thenAnswer(call -> {
            TripReplanJob saved = mock(TripReplanJob.class);
            when(saved.getId()).thenReturn(ids.incrementAndGet());
            return saved;
        });
        TripReplanJobService worker = mock(TripReplanJobService.class);
        return new ReplanFixture(user, new TripReplanController(users, trips, jobs, worker), jobs, worker);
    }

    @Test
    void 장소_한도를_소진해도_재구성은_202로_접수된다() {
        ReplanFixture fixture = replanFixture();
        DailyQuotaService quota = new DailyQuotaService(mock(ProcessingJobRepository.class), 20, 1);
        quota.consumePlaceCall(fixture.user());
        assertThatThrownBy(() -> quota.consumePlaceCall(fixture.user()))
                .isInstanceOf(DailyQuotaService.QuotaExceededException.class);
        assertThat(fixture.controller().replan(mock(Authentication.class), 9L,
                new TripReplanController.TripReplanRequest(true, false)).getStatusCode().value()).isEqualTo(202);
        verify(fixture.worker()).process(1L);
    }

    @Test
    void 같은_재구성을_동시에_조회하면_서로_다른_작업을_둘_만든다() throws Exception {
        ReplanFixture fixture = replanFixture();
        CyclicBarrier bothRead = new CyclicBarrier(2);
        when(fixture.jobs().findByUserAndTripAndIndoorOnlyAndAllPlacesAndStatusInAndUpdatedAtAfter(
                any(), any(), any(Boolean.class), any(Boolean.class), any(), any())).thenAnswer(call -> {
                    bothRead.await(5, TimeUnit.SECONDS);
                    return List.of();
                });
        try (java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Long> submit = () -> {
                Object body = fixture.controller().replan(mock(Authentication.class), 9L,
                        new TripReplanController.TripReplanRequest(true, false)).getBody();
                return ((TripReplanController.CreateReplanJobResponse) body).jobId();
            };
            java.util.concurrent.Future<Long> first = pool.submit(submit);
            java.util.concurrent.Future<Long> second = pool.submit(submit);
            assertThat(first.get(10, TimeUnit.SECONDS)).isNotEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        verify(fixture.jobs(), times(2)).save(any());
        verify(fixture.worker(), times(2)).process(anyLong());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class AuditFixture {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:review-audit;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean JdbcTemplate jdbc(DataSource source) {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.execute("create table if not exists review_actions(id bigint primary key)");
            jdbc.update("delete from review_actions");
            return jdbc;
        }
        @Bean AdminActionHandler fixtureAction(JdbcTemplate jdbc) { return new CommittedAction(jdbc); }
        @Bean ConfirmTokenStore tokens() { return new ConfirmTokenStore(Clock.systemUTC()); }
        @Bean AdminAuditLog audit() {
            AdminActionLogRepository repository = mock(AdminActionLogRepository.class);
            when(repository.save(any())).thenThrow(new IllegalStateException("injected audit failure"));
            return new AdminAuditLog(repository);
        }
        @Bean AdminActionService actions(List<AdminActionHandler> handlers, ConfirmTokenStore tokens, AdminAuditLog audit) {
            return new AdminActionService(handlers, tokens, audit);
        }
    }
    public static class CommittedAction implements AdminActionHandler {
        private final JdbcTemplate jdbc;
        CommittedAction(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Override public AdminAction action() { return AdminAction.HIDE_DRAFT; }
        @Override public ActionPreview preview(Long id, Long admin) {
            return new ActionPreview(action(), id, 8L, "fixture", null);
        }
        @Override @Transactional public String execute(Long id, Long admin) {
            jdbc.update("insert into review_actions(id) values (?)", id);
            return "fixture committed";
        }
    }
    @Test
    void 감사_저장이_실패해도_앞서_커밋한_관리자_조치는_남는다() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AuditFixture.class)) {
            assertThat(AopUtils.isAopProxy(context.getBean(AdminActionHandler.class))).isTrue();
            ConfirmTokenStore tokens = context.getBean(ConfirmTokenStore.class);
            String token = tokens.issue(new ConfirmTokenStore.Pending(9L, AdminAction.HIDE_DRAFT, 1L, 8L, null));
            assertThatThrownBy(() -> context.getBean(AdminActionService.class)
                    .execute(AdminAction.HIDE_DRAFT, token, "review fixture", null, 9L))
                    .isInstanceOf(IllegalStateException.class).hasMessage("injected audit failure");
            assertThat(context.getBean(JdbcTemplate.class).queryForObject("select count(*) from review_actions", Integer.class))
                    .isEqualTo(1);
            assertThat(tokens.consume(token)).isEmpty();
        }
    }

    @Test
    void 상태가_없거나_알수없는_Expo_티켓을_성공으로_해석한다() throws Exception {
        Method parser = HttpExpoPushClient.class.getDeclaredMethod("parseTickets", String.class);
        parser.setAccessible(true);
        Object result = parser.invoke(null, "{\"data\":[{},null,{\"status\":\"unknown\"}]}");
        assertThat((List<?>) result).hasSize(3).allMatch(java.util.Objects::isNull);
    }

    @Test
    void 같은_장소의_동시_캐시_미스는_상세_API를_두번_부른다() throws Exception {
        PlaceRepository places = mock(PlaceRepository.class);
        when(places.findById(1L)).thenAnswer(call -> Optional.of(
                new Place("fixture-id", "fixture-place", "cafe", 4.0, 1, null, 37.0, 127.0, null)));
        GooglePlacesApiClient client = mock(GooglePlacesApiClient.class);
        CyclicBarrier bothCalled = new CyclicBarrier(2);
        when(client.getDetails("fixture-id")).thenAnswer(call -> {
            bothCalled.await(5, TimeUnit.SECONDS);
            return new GooglePlacesDetailsResponse("fixture-id", List.of(), null);
        });
        PlaceReviewService service = new PlaceReviewService(places, client, mock(ReviewSummaryRunner.class), mock(ApiCallLogService.class));
        try (java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<?> first = pool.submit(() -> service.getOrGenerateSummary(1L));
            java.util.concurrent.Future<?> second = pool.submit(() -> service.getOrGenerateSummary(1L));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        verify(client, times(2)).getDetails("fixture-id");
    }
}
