package com.trova.backend.push;

import com.zerodeplibs.webpush.VAPIDKeyPair;
import com.zerodeplibs.webpush.VAPIDKeyPairs;
import com.zerodeplibs.webpush.key.PrivateKeySources;
import com.zerodeplibs.webpush.key.PublicKeySources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * 웹 푸시 VAPID 키(ADR 0016). 키가 비었거나 형식이 틀리면 알림만 끄고 서버는 그대로 뜬다 —
 * 로컬·테스트는 키 없이 돌고, 운영 .env 복사 실수로 서버가 못 뜨는 일을 막는다.
 */
@Component
public class VapidKeys {

    private static final Logger log = LoggerFactory.getLogger(VapidKeys.class);

    private final String publicKey;
    private final String subject;
    private final VAPIDKeyPair keyPair;

    public VapidKeys(@Value("${app.push.vapid-public-key:}") String publicKey,
                     @Value("${app.push.vapid-private-key:}") String privateKey,
                     @Value("${app.push.vapid-subject:mailto:trovapp26@gmail.com}") String subject) {
        this.publicKey = publicKey == null ? "" : publicKey.trim();
        this.subject = subject;
        this.keyPair = load(this.publicKey, privateKey == null ? "" : privateKey.trim());
    }

    private static VAPIDKeyPair load(String publicKey, String privateKey) {
        if (publicKey.isEmpty() || privateKey.isEmpty()) {
            log.info("웹 푸시 꺼짐: VAPID 키가 설정되지 않았어요");
            return null;
        }
        try {
            return VAPIDKeyPairs.of(
                    PrivateKeySources.ofPKCS8Bytes(Base64.getDecoder().decode(privateKey)),
                    PublicKeySources.ofUncompressedBytes(Base64.getUrlDecoder().decode(publicKey)));
        } catch (RuntimeException e) {
            log.warn("웹 푸시 꺼짐: VAPID 키 형식이 올바르지 않아요({})", e.getClass().getSimpleName());
            return null;
        }
    }

    public boolean isEnabled() {
        return keyPair != null;
    }

    public String publicKey() {
        return publicKey;
    }

    public String subject() {
        return subject;
    }

    public VAPIDKeyPair keyPair() {
        return keyPair;
    }
}
