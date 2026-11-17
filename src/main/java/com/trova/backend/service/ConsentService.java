package com.trova.backend.service;

import com.trova.backend.entity.ConsentType;
import com.trova.backend.entity.User;
import com.trova.backend.entity.UserConsent;
import com.trova.backend.repository.UserConsentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConsentService {

    // 연령 확인은 약관·방침의 개정과 무관하다. version은 확인한 기준 연령을 저장한다.
    private static final String AGE_VERSION = "14";

    private final UserConsentRepository consentRepository;
    private final String termsVersion;
    private final String privacyVersion;

    public ConsentService(
            UserConsentRepository consentRepository,
            @Value("${app.consent.terms-version:2026-10-14}") String termsVersion,
            @Value("${app.consent.privacy-version:2026-10-14}") String privacyVersion
    ) {
        this.consentRepository = consentRepository;
        this.termsVersion = termsVersion;
        this.privacyVersion = privacyVersion;
    }

    public String getTermsVersion() {
        return termsVersion;
    }

    public String getPrivacyVersion() {
        return privacyVersion;
    }

    @Transactional(readOnly = true)
    public boolean needed(User user) {
        return !consentRepository.existsByUserAndTypeAndVersion(user, ConsentType.TERMS, termsVersion)
                || !consentRepository.existsByUserAndTypeAndVersion(user, ConsentType.PRIVACY, privacyVersion)
                || !consentRepository.existsByUserAndType(user, ConsentType.AGE_OVER_14);
    }

    @Transactional
    public void record(User user, String termsVersion, String privacyVersion, Boolean ageOver14) {
        if (!this.termsVersion.equals(termsVersion) || !this.privacyVersion.equals(privacyVersion)
                || !Boolean.TRUE.equals(ageOver14)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "현재 버전 약관·개인정보 동의와 만 14세 이상 확인이 필요합니다");
        }
        recordVersion(user, ConsentType.TERMS, termsVersion);
        recordVersion(user, ConsentType.PRIVACY, privacyVersion);
        if (!consentRepository.existsByUserAndType(user, ConsentType.AGE_OVER_14)) {
            consentRepository.save(new UserConsent(user, ConsentType.AGE_OVER_14, AGE_VERSION));
        }
    }

    private void recordVersion(User user, ConsentType type, String version) {
        if (!consentRepository.existsByUserAndTypeAndVersion(user, type, version)) {
            consentRepository.save(new UserConsent(user, type, version));
        }
    }
}
