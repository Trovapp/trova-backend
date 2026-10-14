package com.trova.backend.push;

import com.zerodeplibs.webpush.VAPIDKeyPair;
import com.zerodeplibs.webpush.VAPIDKeyPairs;
import com.zerodeplibs.webpush.key.PrivateKeySource;
import com.zerodeplibs.webpush.key.PrivateKeySources;
import com.zerodeplibs.webpush.key.PublicKeySource;
import com.zerodeplibs.webpush.key.PublicKeySources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
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
            PrivateKeySource privateSource = PrivateKeySources.ofPKCS8Bytes(Base64.getDecoder().decode(privateKey));
            PublicKeySource publicSource = PublicKeySources.ofUncompressedBytes(Base64.getUrlDecoder().decode(publicKey));
            if (!isPair(privateSource.extract(), publicSource.extract())) {
                // 짝이 아니면 켜진 채 떠서 모든 발송이 403이 된다(#200) — 운영 .env 복사 실수 대비.
                log.warn("웹 푸시 꺼짐: VAPID 공개 키와 비공개 키가 짝이 아니에요");
                return null;
            }
            return VAPIDKeyPairs.of(privateSource, publicSource);
        } catch (RuntimeException e) {
            log.warn("웹 푸시 꺼짐: VAPID 키 형식이 올바르지 않아요({})", e.getClass().getSimpleName());
            return null;
        }
    }

    /** 비공개 키로 서명한 것을 공개 키로 검증해 본다. */
    private static boolean isPair(PrivateKey privateKey, PublicKey publicKey) {
        try {
            byte[] probe = "trova-vapid-check".getBytes(StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(probe);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(probe);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
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
