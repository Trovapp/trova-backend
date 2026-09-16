package com.trova.backend.replan;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.TransportMode;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.recommendation.AlternativeFilter;
import com.trova.backend.recommendation.AlternativeFinderService;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.service.ApiCallLogService;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.serializer.std.NullableObjectSerializer;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * "전체 일정 재구성" — 여행의 여러 장소를 순회하며 조건(v1: 실내 위주)에 안 맞는
 * 장소마다 대안을 찾고, 이웃 장소와 이동시간 충돌이 있으면 다음 순위 후보로
 * 백트래킹한다(LangGraph4j 사이클). 새 추천 로직은 없다 — 후보 조회는 기존
 * AlternativeFinderService를 타겟당 정확히 1회만 호출한다.
 *
 * 그래프: identify_targets -> route_next
 *   route_next --(더 처리할 타겟 있음)--> fetch_candidates
 *   route_next --(없음)--> END
 *   fetch_candidates --(후보 있음)--> check_conflict
 *   fetch_candidates --(후보 없음: 즉시 실패)--> route_next
 *   check_conflict --(충돌, 다음 후보 있음)--> check_conflict(사이클)
 *   check_conflict --(확정 또는 후보 소진)--> route_next
 */
@Component
public class TripReplanGraph {

    private static final int MAX_TARGETS = 10;
    private static final int MAX_CANDIDATE_TRIES = 3;
    private static final int CONFLICT_THRESHOLD_MINUTES = 30;

    // LangGraph4j 1.8.13은 "노드 실행 1회 = iteration 1"로 세고(실제 소스
    // CompiledGraph.AsyncNodeGenerator.next — ++iteration이 매 next() 호출마다
    // 실행되고, 노드 하나당 next()가 정확히 한 번 호출됨), CompileConfig에 아무
    // 것도 안 넘긴 기본 compile()은 recursionLimit=25다. 이 그래프의 실제
    // 노드/엣지 구조로 총 iteration 수를 직접 세면:
    //   총 iteration = 3                              (START 1 + END 1 + 완료감지 1)
    //                + 1                              (identify_targets, 1회)
    //                + (타겟수 + 1)                     (route_next — 타겟마다 1회 + 마지막 종료판정 1회)
    //                + 타겟수                           (fetch_candidates — 타겟마다 1회)
    //                + Σ(타겟별 check_conflict 호출수)   (후보 없으면 0, 백트래킹 최악의 경우 타겟당 MAX_CANDIDATE_TRIES)
    //   = 2*MAX_TARGETS + 5 + Σ(check_conflict 호출수)
    // 검증: 후보가 전부 빈 리스트인 10타겟 케이스(체크컨플릭트 0회)는
    //   2*10+5+0 = 25 — 리뷰어가 실측한 "정확히 기본값 25에 걸려 통과"와 정확히
    // 일치한다. 최악(모든 타겟이 MAX_CANDIDATE_TRIES회 백트래킹) 기준
    //   2*10+5+10*3 = 55. 라이브러리 내부 오버헤드에 대한 여유를 더해 넉넉히 잡는다.
    private static final int RECURSION_LIMIT =
            2 * MAX_TARGETS + 5 + MAX_TARGETS * MAX_CANDIDATE_TRIES + 15; // = 70 (MAX_TARGETS=10, MAX_CANDIDATE_TRIES=3 기준)

    private final AlternativeFinderService alternativeFinderService;
    private final ItineraryRepository itineraryRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final ApiCallLogService apiCallLogService;
    private final CompiledGraph<TripReplanState> compiledGraph;

    // LangGraph4j는 매 노드 실행마다 상태를 자바 직렬화(ObjectOutputStream)로
    // 클론한다(체크포인트 저장 여부와 무관하게 CompiledGraph.buildNodeOutput이 항상
    // cloneState를 호출함 — 1.8.13 실제 동작, 실행해서 NotSerializableException으로
    // 확인). User 엔티티는 Serializable이 아니고(수정 금지 대상), 그래프 실행 내내
    // 값이 바뀌지 않으므로 상태 맵에 넣는 대신 스레드 로컬로 들고 다닌다 — run()이
    // 동기 호출이라 동일 스레드에서 fetchCandidates가 안전하게 읽는다.
    private final ThreadLocal<User> currentUser = new ThreadLocal<>();

    public TripReplanGraph(
            AlternativeFinderService alternativeFinderService,
            ItineraryRepository itineraryRepository,
            TripPlaceRepository tripPlaceRepository,
            ApiCallLogService apiCallLogService
    ) {
        this.alternativeFinderService = alternativeFinderService;
        this.itineraryRepository = itineraryRepository;
        this.tripPlaceRepository = tripPlaceRepository;
        this.apiCallLogService = apiCallLogService;
        try {
            CompileConfig compileConfig = CompileConfig.builder()
                    .recursionLimit(RECURSION_LIMIT)
                    .build();
            this.compiledGraph = buildGraph().compile(compileConfig);
        } catch (GraphStateException e) {
            throw new IllegalStateException("TripReplanGraph 그래프 구성 실패", e);
        }
    }

    public record ReplanMatch(Long tripPlaceId, String originalName, AlternativeCandidate candidate) {
    }

    public record ReplanOutcome(List<ReplanMatch> matches, List<Long> failedTripPlaceIds) {
    }

    public ReplanOutcome run(User user, Trip trip, boolean indoorOnly) {
        long start = System.currentTimeMillis();
        List<Itinerary> itineraries = itineraryRepository.findByTripOrderByDay(trip);
        List<TripPlace> orderedPlaces = new ArrayList<>();
        for (Itinerary itinerary : itineraries) {
            orderedPlaces.addAll(tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary));
        }

        // dayId(원본 Itinerary id)를 스냅샷에 함께 담아, 여러 날짜를 한 리스트로
        // 펼친 뒤에도 이웃 충돌 판정이 날짜 경계를 넘지 않도록 한다(예: 1일차
        // 마지막 장소와 2일차 첫 장소는 서로 이웃이 아니다).
        List<TripReplanState.PlaceSnapshot> snapshots = orderedPlaces.stream()
                .map(p -> new TripReplanState.PlaceSnapshot(
                        p.getId(), p.getLatitude(), p.getLongitude(), p.getSpace(), p.getItinerary().getId()))
                .toList();

        Map<String, Object> initial = new HashMap<>();
        initial.put(TripReplanState.INDOOR_ONLY_KEY, indoorOnly);
        initial.put(TripReplanState.PLACES_KEY, snapshots);
        initial.put(TripReplanState.CURSOR_KEY, 0);
        // MATCHES_KEY/FAILED_KEY는 appender 채널의 기본값(빈 리스트)에 기대지 않고
        // 여기서 명시적으로 빈 리스트로 시작한다 — 타겟이 하나도 없어 두 키가 한 번도
        // 갱신되지 않는 경우에도 finalState.matches()/failed()가 항상 안전하게
        // 빈 리스트를 반환하게 한다.
        initial.put(TripReplanState.MATCHES_KEY, new ArrayList<TripReplanState.Match>());
        initial.put(TripReplanState.FAILED_KEY, new ArrayList<Long>());

        currentUser.set(user);
        try {
            TripReplanState finalState = compiledGraph.invoke(initial)
                    .orElseThrow(() -> new IllegalStateException("일정 재구성 그래프가 결과를 반환하지 않았습니다"));

            Map<Long, String> nameByTripPlaceId = new HashMap<>();
            for (TripPlace p : orderedPlaces) {
                nameByTripPlaceId.put(p.getId(), p.getPlaceName());
            }

            List<ReplanMatch> matches = finalState.matches().stream()
                    .map(m -> new ReplanMatch(m.tripPlaceId(), nameByTripPlaceId.get(m.tripPlaceId()), m.candidate()))
                    .toList();

            apiCallLogService.record(
                    "internal", "trip-replan", null, System.currentTimeMillis() - start, true,
                    null, null, null, null);

            return new ReplanOutcome(matches, finalState.failed());
        } catch (RuntimeException e) {
            apiCallLogService.record(
                    "internal", "trip-replan", null, System.currentTimeMillis() - start, false,
                    e.getMessage(), null, null, null);
            throw e;
        } finally {
            currentUser.remove();
        }
    }

    private StateGraph<TripReplanState> buildGraph() throws GraphStateException {
        StateGraph<TripReplanState> stateGraph =
                new StateGraph<>(TripReplanState.SCHEMA, initData -> new TripReplanState(initData));
        registerCustomSerializers(stateGraph);
        return stateGraph
                .addNode("identify_targets", node_async(this::identifyTargets))
                .addNode("route_next", node_async(state -> Map.of()))
                .addNode("fetch_candidates", node_async(this::fetchCandidates))
                .addNode("check_conflict", node_async(this::checkConflict))
                .addEdge(START, "identify_targets")
                .addEdge("identify_targets", "route_next")
                .addConditionalEdges("route_next", edge_async(this::routeNext),
                        Map.of("has_more", "fetch_candidates", "done", END))
                .addConditionalEdges("fetch_candidates", edge_async(state -> state.candidates().isEmpty() ? "empty" : "found"),
                        Map.of("found", "check_conflict", "empty", "route_next"))
                .addConditionalEdges("check_conflict", edge_async(this::routeConflict),
                        Map.of("retry", "check_conflict", "resolved", "route_next"));
    }

    // --- 노드 구현 ---

    private Map<String, Object> identifyTargets(TripReplanState state) {
        boolean indoorOnly = state.indoorOnly();
        List<TripReplanState.PlaceSnapshot> places = state.places();
        List<Integer> targetIndexes = new ArrayList<>();
        for (int i = 0; i < places.size() && targetIndexes.size() < MAX_TARGETS; i++) {
            TripReplanState.PlaceSnapshot p = places.get(i);
            if (p.space() == null || p.latitude() == null || p.longitude() == null) {
                continue;
            }
            boolean isIndoor = "INDOOR".equals(p.space());
            if (indoorOnly && !isIndoor) {
                targetIndexes.add(i);
            }
        }
        return Map.of(TripReplanState.TARGET_INDEXES_KEY, targetIndexes);
    }

    private String routeNext(TripReplanState state) {
        return state.cursor() < state.targetIndexes().size() ? "has_more" : "done";
    }

    private Map<String, Object> fetchCandidates(TripReplanState state) {
        int placeIndex = state.targetIndexes().get(state.cursor());
        TripReplanState.PlaceSnapshot target = state.places().get(placeIndex);
        User user = currentUser.get();

        // indoorOnly는 실제 요청 상태(state.indoorOnly())를 그대로 전달한다 — 하드코딩된
        // true는 identifyTargets가 indoorOnly=true일 때만 타겟을 만드는 v1 한정으로만
        // 우연히 맞았을 뿐이다. transportMode는 WALK로 고정한다 — GeoUtils의 충돌
        // 판정과 동일한 4km/h 도보 기준(WALK_SPEED_KMH)을 쓰므로, 후보 응답의
        // estimatedTravelMinutes도 그 기준과 일관되게 채워진다.
        AlternativeFilter filter = new AlternativeFilter(null, state.indoorOnly(), null, null, TransportMode.WALK);
        List<AlternativeCandidate> candidates = alternativeFinderService
                .findAlternatives(user, target.tripPlaceId(), filter)
                .orElse(List.of());

        if (candidates.isEmpty()) {
            return Map.of(
                    TripReplanState.CANDIDATES_KEY, List.<AlternativeCandidate>of(),
                    TripReplanState.CANDIDATE_TRY_KEY, 0,
                    TripReplanState.FAILED_KEY, target.tripPlaceId(),
                    TripReplanState.CURSOR_KEY, state.cursor() + 1
            );
        }
        return Map.of(
                TripReplanState.CANDIDATES_KEY, candidates,
                TripReplanState.CANDIDATE_TRY_KEY, 0
        );
    }

    private String routeConflict(TripReplanState state) {
        return "retry".equals(state.<String>value(TripReplanState.ROUTE_KEY).orElse("")) ? "retry" : "resolved";
    }

    private Map<String, Object> checkConflict(TripReplanState state) {
        int placeIndex = state.targetIndexes().get(state.cursor());
        List<TripReplanState.PlaceSnapshot> places = state.places();
        TripReplanState.PlaceSnapshot originalTarget = places.get(placeIndex);
        List<AlternativeCandidate> candidates = state.candidates();
        int tryIndex = state.candidateTry();
        AlternativeCandidate candidate = candidates.get(tryIndex);

        TripReplanState.PlaceSnapshot prev = placeIndex > 0 ? places.get(placeIndex - 1) : null;
        TripReplanState.PlaceSnapshot next = placeIndex < places.size() - 1 ? places.get(placeIndex + 1) : null;

        boolean conflict = exceedsThreshold(originalTarget, prev, candidate)
                || exceedsThreshold(originalTarget, next, candidate);

        if (conflict && tryIndex + 1 < candidates.size() && tryIndex + 1 < MAX_CANDIDATE_TRIES) {
            return Map.of(
                    TripReplanState.CANDIDATE_TRY_KEY, tryIndex + 1,
                    TripReplanState.ROUTE_KEY, "retry"
            );
        }

        if (conflict) {
            return Map.of(
                    TripReplanState.FAILED_KEY, originalTarget.tripPlaceId(),
                    TripReplanState.CURSOR_KEY, state.cursor() + 1,
                    TripReplanState.ROUTE_KEY, "resolved"
            );
        }

        List<TripReplanState.PlaceSnapshot> updatedPlaces = new ArrayList<>(places);
        updatedPlaces.set(placeIndex, new TripReplanState.PlaceSnapshot(
                originalTarget.tripPlaceId(), candidate.latitude(), candidate.longitude(),
                originalTarget.space(), originalTarget.dayId()));

        return Map.of(
                TripReplanState.PLACES_KEY, updatedPlaces,
                TripReplanState.MATCHES_KEY, new TripReplanState.Match(originalTarget.tripPlaceId(), candidate),
                TripReplanState.CURSOR_KEY, state.cursor() + 1,
                TripReplanState.ROUTE_KEY, "resolved"
        );
    }

    private boolean exceedsThreshold(
            TripReplanState.PlaceSnapshot target, TripReplanState.PlaceSnapshot neighbor, AlternativeCandidate candidate
    ) {
        if (neighbor == null || neighbor.latitude() == null || neighbor.longitude() == null) {
            return false;
        }
        if (candidate.latitude() == null || candidate.longitude() == null) {
            return false;
        }
        // 날짜 경계를 넘는 이웃(예: 1일차 마지막 장소 <-> 2일차 첫 장소)은 애초에
        // 같은 날 동선이 아니므로 이동시간 충돌 판정 대상이 아니다. run()이 리스트를
        // 여러 날짜에 걸쳐 이어붙이기 때문에 이 가드가 없으면 배열 인덱스상으로만
        // "이웃"인 다른 날짜 장소와 비교해 스퓨리어스 충돌이 난다.
        if (!java.util.Objects.equals(target.dayId(), neighbor.dayId())) {
            return false;
        }
        int minutes = GeoUtils.estimatedWalkMinutes(
                neighbor.latitude(), neighbor.longitude(), candidate.latitude(), candidate.longitude());
        return minutes > CONFLICT_THRESHOLD_MINUTES;
    }

    // --- 상태 클론용 커스텀 직렬화 ---
    //
    // LangGraph4j 1.8.13은 노드 실행마다 CompiledGraph.buildNodeOutput ->
    // cloneState가 상태 맵 전체를 ObjectOutputStream 기반으로 직렬화/역직렬화한다
    // (체크포인트 저장 여부와 무관 — 실행해서 NotSerializableException으로 실측
    // 확인). 상태에 들어가는 AlternativeCandidate(기존 레코드, 수정 금지 대상)와
    // TripReplanState.Match/PlaceSnapshot(Task 2 레코드)는 Serializable을 선언하지
    // 않는다. TripReplanState.java(Task 2 산출물)를 건드리지 않고 이 문제를 풀기
    // 위해, 라이브러리가 제공하는 확장점(SerializerMapper.register)에 타입별
    // 커스텀 직렬화기를 등록해 표준 자바 직렬화를 우회한다 — 필드를 하나씩
    // 명시적으로 쓰고 읽으므로 대상 클래스가 Serializable일 필요가 없다.
    private void registerCustomSerializers(StateGraph<TripReplanState> stateGraph) {
        if (stateGraph.getStateSerializer() instanceof ObjectStreamStateSerializer<TripReplanState> serializer) {
            serializer.mapper()
                    .register(AlternativeCandidate.class, new AlternativeCandidateSerializer())
                    .register(TripReplanState.Match.class, new MatchSerializer())
                    .register(TripReplanState.PlaceSnapshot.class, new PlaceSnapshotSerializer());
        }
    }

    private static final class AlternativeCandidateSerializer implements NullableObjectSerializer<AlternativeCandidate> {
        @Override
        public void write(AlternativeCandidate candidate, ObjectOutput out) throws IOException {
            writeNullableObject(candidate.placeId(), out);
            writeNullableUTF(candidate.googlePlaceId(), out);
            writeNullableUTF(candidate.name(), out);
            writeNullableUTF(candidate.category(), out);
            writeNullableObject(candidate.rating(), out);
            writeNullableObject(candidate.userRatingCount(), out);
            writeNullableObject(candidate.latitude(), out);
            writeNullableObject(candidate.longitude(), out);
            writeNullableUTF(candidate.address(), out);
            writeNullableObject(candidate.distanceToNextKm(), out);
            writeNullableObject(candidate.estimatedTravelMinutes(), out);
            writeNullableObject(candidate.isCongestionAvailable(), out);
            writeNullableUTF(candidate.congestionLevel(), out);
            writeNullableUTF(candidate.recommendationReason(), out);
        }

        @Override
        public AlternativeCandidate read(ObjectInput in) throws IOException, ClassNotFoundException {
            Long placeId = (Long) readNullableObject(in).orElse(null);
            String googlePlaceId = readNullableUTF(in).orElse(null);
            String name = readNullableUTF(in).orElse(null);
            String category = readNullableUTF(in).orElse(null);
            Double rating = (Double) readNullableObject(in).orElse(null);
            Integer userRatingCount = (Integer) readNullableObject(in).orElse(null);
            Double latitude = (Double) readNullableObject(in).orElse(null);
            Double longitude = (Double) readNullableObject(in).orElse(null);
            String address = readNullableUTF(in).orElse(null);
            Double distanceToNextKm = (Double) readNullableObject(in).orElse(null);
            Integer estimatedTravelMinutes = (Integer) readNullableObject(in).orElse(null);
            Boolean isCongestionAvailable = (Boolean) readNullableObject(in).orElse(null);
            String congestionLevel = readNullableUTF(in).orElse(null);
            String recommendationReason = readNullableUTF(in).orElse(null);
            return new AlternativeCandidate(placeId, googlePlaceId, name, category, rating, userRatingCount,
                    latitude, longitude, address, distanceToNextKm, estimatedTravelMinutes,
                    isCongestionAvailable, congestionLevel, recommendationReason);
        }
    }

    private static final class MatchSerializer implements NullableObjectSerializer<TripReplanState.Match> {
        @Override
        public void write(TripReplanState.Match match, ObjectOutput out) throws IOException {
            writeNullableObject(match.tripPlaceId(), out);
            writeNullableObject(match.candidate(), out);
        }

        @Override
        public TripReplanState.Match read(ObjectInput in) throws IOException, ClassNotFoundException {
            Long tripPlaceId = (Long) readNullableObject(in).orElse(null);
            AlternativeCandidate candidate = (AlternativeCandidate) readNullableObject(in).orElse(null);
            return new TripReplanState.Match(tripPlaceId, candidate);
        }
    }

    private static final class PlaceSnapshotSerializer implements NullableObjectSerializer<TripReplanState.PlaceSnapshot> {
        @Override
        public void write(TripReplanState.PlaceSnapshot snapshot, ObjectOutput out) throws IOException {
            writeNullableObject(snapshot.tripPlaceId(), out);
            writeNullableObject(snapshot.latitude(), out);
            writeNullableObject(snapshot.longitude(), out);
            writeNullableUTF(snapshot.space(), out);
            writeNullableObject(snapshot.dayId(), out);
        }

        @Override
        public TripReplanState.PlaceSnapshot read(ObjectInput in) throws IOException, ClassNotFoundException {
            Long tripPlaceId = (Long) readNullableObject(in).orElse(null);
            Double latitude = (Double) readNullableObject(in).orElse(null);
            Double longitude = (Double) readNullableObject(in).orElse(null);
            String space = readNullableUTF(in).orElse(null);
            Long dayId = (Long) readNullableObject(in).orElse(null);
            return new TripReplanState.PlaceSnapshot(tripPlaceId, latitude, longitude, space, dayId);
        }
    }
}
