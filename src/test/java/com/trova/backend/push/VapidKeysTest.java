package com.trova.backend.push;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class VapidKeysTest {

    /** 테스트용 키 쌍 — [0]=공개 키(base64url 비압축), [1]=비공개 키(PKCS8 base64). */
    static String[] newKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        return new String[] {
                Base64.getUrlEncoder().withoutPadding().encodeToString(uncompressed((ECPublicKey) pair.getPublic())),
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
        };
    }

    static byte[] uncompressed(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 4;
        copy32(key.getW().getAffineX().toByteArray(), out, 1);
        copy32(key.getW().getAffineY().toByteArray(), out, 33);
        return out;
    }

    private static void copy32(byte[] src, byte[] dst, int offset) {
        int len = Math.min(32, src.length);
        System.arraycopy(src, src.length - len, dst, offset + 32 - len, len);
    }

    @Test
    void 키가_비어_있으면_꺼진다() {
        VapidKeys keys = new VapidKeys("", "", "mailto:a@b.c");
        assertThat(keys.isEnabled()).isFalse();
    }

    @Test
    void 올바른_키면_켜지고_공개_키를_그대로_돌려준다() throws Exception {
        String[] k = newKeys();
        VapidKeys keys = new VapidKeys(k[0], k[1], "mailto:a@b.c");
        assertThat(keys.isEnabled()).isTrue();
        assertThat(keys.publicKey()).isEqualTo(k[0]);
        assertThat(keys.keyPair()).isNotNull();
    }

    @Test
    void 키_형식이_잘못돼도_서버는_뜨고_알림만_꺼진다() {
        VapidKeys keys = new VapidKeys("not-a-key", "also-wrong", "mailto:a@b.c");
        assertThat(keys.isEnabled()).isFalse();
    }
}
