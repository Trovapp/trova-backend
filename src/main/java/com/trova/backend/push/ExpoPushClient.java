package com.trova.backend.push;

import java.util.List;
import java.util.Map;

/** Expo 푸시 서버에 알림 묶음을 보낸다. 테스트는 가짜로 바꾼다. */
public interface ExpoPushClient {

    /**
     * @param status HTTP 상태(연결 실패 등 예외는 -1)
     * @param ticketErrors 보낸 순서대로 티켓 오류 코드(성공은 null). HTTP가 200이 아니면 빈 목록.
     */
    record Response(int status, List<String> ticketErrors) { }

    Response send(List<Map<String, Object>> messages) throws Exception;
}
