package com.trova.backend.controller;

import com.trova.backend.admin.RequestRateLimiter;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.DailyQuotaService;
import com.trova.backend.service.TripService;
import com.trova.backend.service.TripShareLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TripShareLinkControllerCopyTest {

    @Test
    void 복사_서비스가_예외를_던지면_하루_한도를_복원하고_같은_예외를_던진다() {
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        TripService tripService = mock(TripService.class);
        TripShareLinkService shareLinkService = mock(TripShareLinkService.class);
        RequestRateLimiter rateLimiter = mock(RequestRateLimiter.class);
        DailyQuotaService quota = new DailyQuotaService(mock(ProcessingJobRepository.class), 10, 100, 2);
        TripShareLinkController controller = new TripShareLinkController(
                currentUserService, mock(TripRepository.class), shareLinkService,
                mock(ItineraryRepository.class), mock(TripPlaceRepository.class), rateLimiter,
                tripService, quota, 60);
        Authentication authentication = mock(Authentication.class);
        User target = mock(User.class);
        when(target.getId()).thenReturn(1L);
        Trip source = new Trip(target, "원본", null, null);
        Trip copy = new Trip(target, "사본", null, null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(rateLimiter.tryAcquire("public-trip:" + request.getRemoteAddr(), 60)).thenReturn(true);
        when(currentUserService.resolve(authentication)).thenReturn(target);
        when(shareLinkService.findPublic("token")).thenReturn(Optional.of(source));
        RuntimeException failure = new IllegalStateException("강제 복사 실패");
        when(tripService.copyTrip(target, source)).thenThrow(failure).thenReturn(copy);

        assertThatThrownBy(() -> controller.copyPublicTrip(authentication, "token", request)).isSameAs(failure);
        assertThat(controller.copyPublicTrip(authentication, "token", request).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(controller.copyPublicTrip(authentication, "token", request).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThatThrownBy(() -> controller.copyPublicTrip(authentication, "token", request))
                .isInstanceOf(DailyQuotaService.QuotaExceededException.class);
        verify(tripService, times(3)).copyTrip(target, source);
    }
}
