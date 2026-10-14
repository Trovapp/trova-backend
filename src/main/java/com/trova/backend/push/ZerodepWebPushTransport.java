package com.trova.backend.push;

import com.trova.backend.entity.PushSubscription;
import com.zerodeplibs.webpush.httpclient.StandardHttpClientRequestPreparer;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** 표준 웹 푸시(RFC 8030/8291/8292) 실제 발송 — zerodep 라이브러리로 암호화·VAPID 서명, 전송은 Java 기본 HttpClient. */
@Component
public class ZerodepWebPushTransport implements WebPushTransport {

    private final VapidKeys keys;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ZerodepWebPushTransport(VapidKeys keys) {
        this.keys = keys;
    }

    @Override
    public int send(PushSubscription subscription, byte[] payload, String topic) throws Exception {
        com.zerodeplibs.webpush.PushSubscription target = new com.zerodeplibs.webpush.PushSubscription();
        target.setEndpoint(subscription.getEndpoint());
        com.zerodeplibs.webpush.PushSubscription.Keys targetKeys = new com.zerodeplibs.webpush.PushSubscription.Keys();
        targetKeys.setP256dh(subscription.getP256dh());
        targetKeys.setAuth(subscription.getAuth());
        target.setKeys(targetKeys);

        HttpRequest request = StandardHttpClientRequestPreparer.getBuilder()
                .pushSubscription(target)
                .vapidJWTExpiresAfter(15, TimeUnit.MINUTES)
                .vapidJWTSubject(keys.subject())
                .pushMessage(payload)
                .ttl(24, TimeUnit.HOURS)
                .urgencyNormal()
                .topic(topic)
                .build(keys.keyPair())
                .toRequestBuilder()
                .timeout(Duration.ofSeconds(10))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
