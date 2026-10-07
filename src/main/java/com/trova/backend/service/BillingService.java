package com.trova.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.TravelPass;
import com.trova.backend.entity.User;
import com.trova.backend.repository.TravelPassRepository;
import com.trova.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * 여행 패스 구매 기록(#130). 앱이 StoreKit 2 거래(서명된 JWS)를 보내면 30일 패스를 붙인다.
 *
 * - 남의 거래를 가져와 쓰지 못하게, 앱이 결제할 때 회원별 appAccountToken을 넣고 서버가 같은지 확인한다.
 * - 같은 거래를 다시 보내면(앱 재시작·재시도) 새로 만들지 않고 이미 붙인 패스를 돌려준다.
 * - 이미 쓰는 패스가 있으면 끝나는 날부터 이어서 30일.
 * - 지금 확인할 수 있는 건 Xcode StoreKit 테스트 거래뿐이고, 개발 설정(app.billing.allow-xcode-transactions)에서만 받는다
 *   — 그 서명은 Xcode 로컬 인증서라 Apple 루트로 검증되지 않는다. 샌드박스·운영 거래는 Apple 개발자 계정으로
 *   서명 검증(App Store Server Library)을 붙인 뒤에 받는다(사용자 결정: 계정·운영 반영은 나중).
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    static final Duration PASS_LENGTH = Duration.ofDays(30);

    private final TravelPassRepository travelPassRepository;
    private final UserRepository userRepository;
    private final PlanService planService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String productId;
    private final String bundleId;
    private final boolean allowXcodeTransactions;
    private final Clock clock;

    @Autowired
    public BillingService(TravelPassRepository travelPassRepository, UserRepository userRepository, PlanService planService,
                          @Value("${app.billing.travel-pass-product-id:com.trovapp.trova.travelpass30}") String productId,
                          @Value("${app.billing.bundle-id:com.trovapp.trova}") String bundleId,
                          @Value("${app.billing.allow-xcode-transactions:false}") boolean allowXcodeTransactions) {
        this(travelPassRepository, userRepository, planService, productId, bundleId, allowXcodeTransactions, Clock.systemDefaultZone());
    }

    BillingService(TravelPassRepository travelPassRepository, UserRepository userRepository, PlanService planService,
                   String productId, String bundleId, boolean allowXcodeTransactions, Clock clock) {
        this.travelPassRepository = travelPassRepository;
        this.userRepository = userRepository;
        this.planService = planService;
        this.productId = productId;
        this.bundleId = bundleId;
        this.allowXcodeTransactions = allowXcodeTransactions;
        this.clock = clock;
    }

    public String productId() {
        return productId;
    }

    /** 회원마다 늘 같은 UUID — 앱이 결제 요청에 넣고, 서버가 거래 속 값과 맞춰 본다. */
    public static UUID appAccountToken(User user) {
        return UUID.nameUUIDFromBytes(("trova-user-" + user.getId()).getBytes(StandardCharsets.UTF_8));
    }

    @Transactional
    public TravelPass recordAppleTransaction(User user, String signedTransaction) {
        JsonNode tx = decodePayload(signedTransaction);
        String environment = text(tx, "environment");
        if (!"Xcode".equals(environment)) {
            throw new BillingException(Reason.NOT_SUPPORTED, "아직 실제 결제는 받을 수 없어요. 준비되면 알려드릴게요.");
        }
        if (!allowXcodeTransactions) {
            throw new BillingException(Reason.NOT_SUPPORTED, "테스트 결제는 개발 서버에서만 받을 수 있어요.");
        }
        if (!bundleId.equals(text(tx, "bundleId")) || !productId.equals(text(tx, "productId"))) {
            throw new BillingException(Reason.INVALID, "알 수 없는 상품이에요.");
        }
        if (!appAccountToken(user).toString().equalsIgnoreCase(text(tx, "appAccountToken"))) {
            throw new BillingException(Reason.FORBIDDEN, "이 계정의 결제가 아니에요.");
        }
        String transactionId = text(tx, "transactionId");
        if (transactionId == null || transactionId.isBlank()) {
            throw new BillingException(Reason.INVALID, "결제 정보를 읽지 못했어요.");
        }
        // 같은 회원의 기록은 한 줄로 처리한다(QA 2026-10-07): 잠그지 않으면 동시에 온 다른 거래 둘이 같은 "끝나는 날"을 보고
        // 기간이 겹쳤고(두 번 결제에 30일), 같은 거래가 동시에 오면 유일 제약에 걸려 500이 났다.
        userRepository.findByIdForUpdate(user.getId());
        Optional<TravelPass> existing = travelPassRepository.findByTransactionId(transactionId);
        if (existing.isPresent()) {
            if (!existing.get().getUser().getId().equals(user.getId())) {
                throw new BillingException(Reason.FORBIDDEN, "이 계정의 결제가 아니에요.");
            }
            return existing.get();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime start = planService.activePass(user).map(TravelPass::getExpiresAt).filter(e -> e.isAfter(now)).orElse(now);
        TravelPass pass = travelPassRepository.save(new TravelPass(user, transactionId, productId, environment,
                start, start.plus(PASS_LENGTH), now));
        log.info("여행 패스 기록(userId={}, transactionId={}, environment={}, ~{})", user.getId(), transactionId, environment, pass.getExpiresAt());
        return pass;
    }

    private JsonNode decodePayload(String jws) {
        String[] parts = jws == null ? new String[0] : jws.split("\\.");
        if (parts.length != 3) {
            throw new BillingException(Reason.INVALID, "결제 정보를 읽지 못했어요.");
        }
        try {
            return objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (Exception e) {
            throw new BillingException(Reason.INVALID, "결제 정보를 읽지 못했어요.");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    public enum Reason { INVALID, FORBIDDEN, NOT_SUPPORTED }

    public static class BillingException extends RuntimeException {
        private final Reason reason;

        public BillingException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() { return reason; }
    }
}
