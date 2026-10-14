package com.trova.backend.push;

import com.trova.backend.entity.PushSubscription;

/** 구독 하나에 암호화한 알림을 보내고 푸시 서버의 HTTP 상태 코드를 돌려준다. 테스트는 가짜로 바꾼다. */
public interface WebPushTransport {

    int send(PushSubscription subscription, byte[] payload, String topic) throws Exception;
}
