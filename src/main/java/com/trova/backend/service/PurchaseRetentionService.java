package com.trova.backend.service;

import com.trova.backend.entity.RetainedPurchase;
import com.trova.backend.entity.TravelPass;
import com.trova.backend.entity.User;
import com.trova.backend.repository.RetainedPurchaseRepository;
import com.trova.backend.repository.TravelPassRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 탈퇴 시 여행 패스 구매 기록 분리 보관(#130). 개인정보처리방침: "구매 기록은 전자상거래법에 따라 5년 보관 뒤 파기,
 * 탈퇴해도 법정 기간 동안은 분리 보관". 회원 데이터는 지체 없이 지우되, 거래 기록만 회원과 끊어 옮긴다.
 */
@Service
public class PurchaseRetentionService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseRetentionService.class);
    static final int RETENTION_YEARS = 5;

    private final TravelPassRepository travelPassRepository;
    private final RetainedPurchaseRepository retainedPurchaseRepository;
    private final Clock clock;

    @Autowired
    public PurchaseRetentionService(TravelPassRepository travelPassRepository, RetainedPurchaseRepository retainedPurchaseRepository) {
        this(travelPassRepository, retainedPurchaseRepository, Clock.systemDefaultZone());
    }

    PurchaseRetentionService(TravelPassRepository travelPassRepository, RetainedPurchaseRepository retainedPurchaseRepository,
                             Clock clock) {
        this.travelPassRepository = travelPassRepository;
        this.retainedPurchaseRepository = retainedPurchaseRepository;
        this.clock = clock;
    }

    /** 회원의 패스를 분리 보관으로 옮기고 원래 기록을 지운다. 보관 기간은 구매일부터 5년. 회원 탈퇴 트랜잭션 안에서 부른다. */
    @Transactional
    public int retainAndRemove(User user) {
        LocalDateTime now = LocalDateTime.now(clock);
        int moved = 0;
        for (TravelPass pass : travelPassRepository.findByUser(user)) {
            if (retainedPurchaseRepository.findByTransactionId(pass.getTransactionId()).isEmpty()) {
                retainedPurchaseRepository.save(new RetainedPurchase(pass, now, pass.getCreatedAt().plusYears(RETENTION_YEARS)));
                moved++;
            }
        }
        travelPassRepository.deleteByUser(user);
        return moved;
    }

    /** 보관 기간이 지난 기록을 하루 한 번 지운다. */
    @Scheduled(fixedRate = 1, initialDelay = 1, timeUnit = TimeUnit.DAYS)
    @Transactional
    public int purgeExpired() {
        int deleted = retainedPurchaseRepository.deleteExpired(LocalDateTime.now(clock));
        if (deleted > 0) {
            log.info("보관 기간(5년)이 지난 구매 기록 {}건 파기", deleted);
        }
        return deleted;
    }
}
