package com.trova.backend.service;

import com.trova.backend.entity.ShareKey;
import com.trova.backend.repository.ShareKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/** 공유 전용 키 발급·재발급·폐기·확인(ADR 0013, #165). */
@Service
public class ShareKeyService {

    public static final String PREFIX = "trv_sk_";
    private static final int KEY_BYTES = 32;

    public record ShareKeyStatus(boolean exists, LocalDateTime createdAt, LocalDateTime lastUsedAt) {
    }

    public record IssuedShareKey(String key, LocalDateTime createdAt) {
    }

    private final ShareKeyRepository repository;
    private final SecureRandom random = new SecureRandom();

    public ShareKeyService(ShareKeyRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ShareKeyStatus status(Long userId) {
        return repository.findByUserId(userId)
                .map(k -> new ShareKeyStatus(true, k.getCreatedAt(), k.getLastUsedAt()))
                .orElse(new ShareKeyStatus(false, null, null));
    }

    /** 새로 만들거나 바꾼다. 원문은 이 응답에서만 볼 수 있다. */
    @Transactional
    public IssuedShareKey issue(Long userId) {
        byte[] bytes = new byte[KEY_BYTES];
        random.nextBytes(bytes);
        String key = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        ShareKey shareKey = repository.findByUserId(userId)
                .map(existing -> {
                    existing.replace(hash(key));
                    return existing;
                })
                .orElseGet(() -> repository.save(new ShareKey(userId, hash(key))));
        return new IssuedShareKey(key, shareKey.getCreatedAt());
    }

    @Transactional
    public void revoke(Long userId) {
        repository.deleteByUserId(userId);
    }

    /** 키가 맞으면 주인 사용자 번호를 돌려주고 마지막 사용 시각을 남긴다. */
    @Transactional
    public Optional<ShareKey> authenticate(String key) {
        if (key == null || !key.startsWith(PREFIX)) {
            return Optional.empty();
        }
        Optional<ShareKey> found = repository.findByKeyHash(hash(key));
        found.ifPresent(ShareKey::markUsed);
        return found;
    }

    static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
