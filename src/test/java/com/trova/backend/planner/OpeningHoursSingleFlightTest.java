package com.trova.backend.planner;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.GooglePlacesApiClient;
import com.trova.backend.recommendation.GooglePlacesHoursResponse;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(20)
class OpeningHoursSingleFlightTest {
    private final GooglePlacesApiClient google = mock(GooglePlacesApiClient.class);
    private final SavedPlaceRepository repository = mock(SavedPlaceRepository.class);
    private final ApiCallLogService logs = mock(ApiCallLogService.class);
    private final OpeningHoursService service = new OpeningHoursService(google, repository, logs);

    private SavedPlace place(String userId, double latitude) {
        User user = new User("google", userId, "fixture", null);
        ProcessingJob job = new ProcessingJob(user, "https://youtu.be/fixture", SourcePlatform.YOUTUBE);
        return new SavedPlace(job, user, "같은 가게", "부산", "cafe", latitude, 129.0);
    }

    private GooglePlacesHoursResponse response() {
        return new GooglePlacesHoursResponse(List.of(new GooglePlacesHoursResponse.Place("google-id",
                new GooglePlacesHoursResponse.Location(35.0, 129.0), null)));
    }

    private record Call(Thread thread, AtomicInteger count, AtomicReference<Throwable> error, AtomicBoolean interrupted) { }

    private Call start(SavedPlace place) {
        AtomicInteger count = new AtomicInteger(-1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            try { count.set(service.fillMissing(List.of(place))); }
            catch (Throwable failure) { error.set(failure); }
            finally { interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        thread.start();
        return new Call(thread, count, error, interrupted);
    }

    private void waiting(Call call) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && call.thread().getState() != Thread.State.WAITING) Thread.sleep(5);
        assertThat(call.thread().getState()).isEqualTo(Thread.State.WAITING);
    }

    private void join(Call... calls) throws Exception {
        for (Call call : calls) {
            call.thread().join(3000);
            if (call.thread().isAlive()) { call.thread().interrupt(); call.thread().join(3000); }
            assertThat(call.thread().isAlive()).isFalse();
        }
    }

    @Test
    void 다른_사용자의_동일한_진행_조회는_외부호출과_기록을_한번만_공유한다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(google.searchTextWithHours(anyString(), anyDouble(), anyDouble(), anyDouble())).thenAnswer(inv -> {
            entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return response();
        });
        SavedPlace first = place("a", 35.0), second = place("b", 35.0);
        Call owner = start(first); Call waiter = null;
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            waiter = start(second); waiting(waiter);
            verify(google, times(1)).searchTextWithHours(anyString(), anyDouble(), anyDouble(), anyDouble());
        } finally { release.countDown(); if (waiter != null) join(owner, waiter); else join(owner); }
        assertThat(owner.error().get()).isNull(); assertThat(waiter.error().get()).isNull();
        assertThat(owner.count().get()).isEqualTo(1); assertThat(waiter.count().get()).isZero();
        assertThat(first.getHoursCheckedAt()).isNotNull(); assertThat(second.getHoursCheckedAt()).isNotNull();
        assertThat(first.getGooglePlaceId()).isEqualTo("google-id");
        verify(repository).save(first); verify(repository).save(second);
        verify(logs, times(1)).record(eq("google-places"), eq("text-search-hours"), isNull(), anyLong(), eq(true), isNull(), isNull(), isNull(), isNull());
        SavedPlace later = place("c", 35.0);
        assertThat(service.fillMissing(List.of(later))).isEqualTo(1);
        verify(google, times(2)).searchTextWithHours(anyString(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void 서로_다른_좌표의_동일_이름은_합치거나_직렬화하지_않는다() throws Exception {
        CountDownLatch both = new CountDownLatch(2), release = new CountDownLatch(1);
        when(google.searchTextWithHours(anyString(), anyDouble(), anyDouble(), anyDouble())).thenAnswer(inv -> {
            both.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return response();
        });
        Call a = start(place("a",35.0)), b = start(place("b",36.0));
        try { assertThat(both.await(3, TimeUnit.SECONDS)).isTrue(); }
        finally { release.countDown(); join(a,b); }
        assertThat(a.count().get()).isEqualTo(1); assertThat(b.count().get()).isEqualTo(1);
    }

    @Test
    void 공유_실패는_확인_표시를_남기지_않고_다음_독립_재시도를_허용한다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        when(google.searchTextWithHours(anyString(), anyDouble(), anyDouble(), anyDouble())).thenAnswer(inv -> {
            if (attempts.incrementAndGet()==1) { entered.countDown(); release.await(5,TimeUnit.SECONDS); throw new IllegalStateException("fixture"); }
            return response();
        });
        SavedPlace first=place("a",35.0), second=place("b",35.0);
        Call a=start(first); Call b=null;
        try { assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue(); b=start(second); waiting(b); }
        finally { release.countDown(); if(b!=null) join(a,b); else join(a); }
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(first.getHoursCheckedAt()).isNull(); assertThat(second.getHoursCheckedAt()).isNull();
        assertThat(a.count().get()).isEqualTo(1); assertThat(b.count().get()).isZero();
        assertThat(service.fillMissing(List.of(second))).isEqualTo(1);
        assertThat(second.getHoursCheckedAt()).isNotNull(); assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void 대기자_인터럽트는_플래그를_보존하고_소유자_조회는_계속한다() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(google.searchTextWithHours(anyString(),anyDouble(),anyDouble(),anyDouble())).thenAnswer(inv->{
            entered.countDown(); assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); return response();
        });
        SavedPlace first=place("a",35.0),second=place("b",35.0);
        Call a=start(first); Call b=null;
        try {
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue(); b=start(second); waiting(b);
            b.thread().interrupt(); b.thread().join(2000);
            assertThat(b.thread().isAlive()).isFalse(); assertThat(b.interrupted().get()).isTrue();
            assertThat(b.count().get()).isZero(); assertThat(a.thread().isAlive()).isTrue();
        } finally { release.countDown(); if(b!=null) join(a,b); else join(a); }
        assertThat(first.getHoursCheckedAt()).isNotNull(); assertThat(second.getHoursCheckedAt()).isNull();
        verify(google,times(1)).searchTextWithHours(anyString(),anyDouble(),anyDouble(),anyDouble());
    }

    @Test
    void 한_사용자의_저장_실패가_공유_응답이나_다른_사용자_저장을_막지_않는다() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        SavedPlace first=place("a",35.0),second=place("b",35.0);
        doAnswer(inv->{ if(inv.getArgument(0)==first) throw new IllegalStateException("save fixture"); return inv.getArgument(0); }).when(repository).save(any(SavedPlace.class));
        when(google.searchTextWithHours(anyString(),anyDouble(),anyDouble(),anyDouble())).thenAnswer(inv->{entered.countDown();release.await(5,TimeUnit.SECONDS);return response();});
        Call a=start(first); Call b=null;
        try { assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();b=start(second);waiting(b); }
        finally { release.countDown();if(b!=null)join(a,b);else join(a); }
        assertThat(second.getHoursCheckedAt()).isNotNull();verify(repository).save(second);
        verify(logs,times(1)).record(eq("google-places"),eq("text-search-hours"),isNull(),anyLong(),eq(true),isNull(),isNull(),isNull(),isNull());
        assertThat(a.error().get()).isNull();assertThat(b.error().get()).isNull();
    }

    @Test
    void 소유자의_Error도_대기자를_깨우고_공유_항목을_제거한다() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger attempts=new AtomicInteger();
        when(google.searchTextWithHours(anyString(),anyDouble(),anyDouble(),anyDouble())).thenAnswer(inv->{
            if(attempts.incrementAndGet()==1){entered.countDown();release.await(5,TimeUnit.SECONDS);throw new AssertionError("fixture");}return response();
        });
        Call a=start(place("a",35.0));Call b=null;
        try{assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();b=start(place("b",35.0));waiting(b);}
        finally{release.countDown();if(b!=null)join(a,b);else join(a);}
        assertThat(a.error().get()).isInstanceOf(AssertionError.class);assertThat(b.error().get()).isNull();
        assertThat(service.fillMissing(List.of(place("c",35.0)))).isEqualTo(1);
        assertThat(attempts.get()).isEqualTo(2);
    }
}
