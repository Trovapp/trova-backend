package com.trova.backend.security;

import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 정지 목록은 빈 초기화 단계에서 채운다 — 서버가 요청을 받기 시작하기 전에 차 있어야 한다(ADR 0014). */
class SuspendedUsersTest {

    @Test
    void 빈_초기화가_끝나면_DB의_정지_계정으로_채운다() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.findSuspendedIds()).thenReturn(List.of(7L, 9L));
        SuspendedUsers suspendedUsers = new SuspendedUsers(repository);

        suspendedUsers.afterSingletonsInstantiated();

        assertThat(suspendedUsers.isSuspended(7L)).isTrue();
        assertThat(suspendedUsers.isSuspended(9L)).isTrue();
        assertThat(suspendedUsers.isSuspended(8L)).isFalse();
    }

    @Test
    void 다시_읽으면_새_목록으로_통째로_바꾼다() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.findSuspendedIds()).thenReturn(List.of(7L), List.of(9L));
        SuspendedUsers suspendedUsers = new SuspendedUsers(repository);
        suspendedUsers.reload();
        suspendedUsers.add(11L);

        suspendedUsers.reload();

        assertThat(suspendedUsers.isSuspended(7L)).isFalse();
        assertThat(suspendedUsers.isSuspended(11L)).isFalse();
        assertThat(suspendedUsers.isSuspended(9L)).isTrue();
        suspendedUsers.add(12L);
        assertThat(suspendedUsers.isSuspended(12L)).isTrue();
    }
}
