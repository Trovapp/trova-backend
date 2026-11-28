package com.trova.backend.recommendation;

import com.trova.backend.config.RequestDbQueryMetrics;
import com.trova.backend.shipandping.RequestIoTime;
import com.trova.backend.entity.Place;
import com.trova.backend.repository.PlaceRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Savepoint;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;

/**
 * Google Places 원시 후보를 Place 카탈로그에 upsert한다(배치 조회로 N+1 방지).
 * RecommendationService(근처 검색)와 PlaceSearchService(텍스트 검색) 양쪽에서 공유한다.
 */
@Service
public class PlaceCatalogService {

    private final PlaceRepository placeRepository;

    private final EntityManager entityManager;

    public PlaceCatalogService(PlaceRepository placeRepository, EntityManager entityManager) {
        this.placeRepository = placeRepository;
        this.entityManager = entityManager;
    }

    /**
     * 후보를 카탈로그에 저장하고 입력 순서대로 돌려준다. 이미 있는 장소는 그대로 쓴다.
     * 없는 장소는 INSERT ... ON CONFLICT DO NOTHING으로 넣고 다시 조회한다 — 같은 검색 결과를
     * 여러 요청이 동시에 저장해도(여러 사용자의 같은 동네 검색 등) 유니크 제약 에러가 나지 않는다(#37).
     */
    @Transactional
    public List<Place> upsertAll(List<GooglePlacesNearbySearchResponse.Place> rawCandidates) {
        Map<String, List<GooglePlacesNearbySearchResponse.Place>> groups = new TreeMap<>();
        for (GooglePlacesNearbySearchResponse.Place raw : rawCandidates) {
            groups.computeIfAbsent(raw.id(), ignored -> new ArrayList<>()).add(raw);
        }
        Map<String, Place> byGoogleId = new HashMap<>();
        placeRepository.findByGooglePlaceIdIn(List.copyOf(groups.keySet()))
                .forEach(place -> byGoogleId.put(place.getGooglePlaceId(), place));

        // INSERT와 좌표 복구 UPDATE를 같은 ID 순서로 실행한다. 마지막 일괄 조회 뒤에는 쓰지 않는다.
        List<String> inserted = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        // 이미 있는 행에 좌표 복구가 필요하거나 중복 입력이면 원래의 ID순 쓰기를 사용한다.
        // 새 장소가 충분히 많을 때만 저장점·해제 왕복을 감수한다.
        List<GooglePlacesNearbySearchResponse.Place> missing = groups.values().stream()
                .map(List::getFirst).filter(raw -> !byGoogleId.containsKey(raw.id())).toList();
        if (missing.size() >= 5 && missing.size() <= 100 && rawCandidates.size() == groups.size()
                && byGoogleId.values().stream().allMatch(p -> p.getLatitude() != null && p.getLongitude() != null)
                && insertAllNew(missing, now)) {
            placeRepository.findByGooglePlaceIdIn(missing.stream().map(GooglePlacesNearbySearchResponse.Place::id).toList())
                    .forEach(place -> byGoogleId.put(place.getGooglePlaceId(), place));
        }
        for (List<GooglePlacesNearbySearchResponse.Place> group : groups.values()) {
            GooglePlacesNearbySearchResponse.Place first = group.getFirst();
            Place place = byGoogleId.get(first.id());
            if (place == null) {
                boolean created = insertIfAbsent(first, now);
                if (created && (first.location() != null || group.size() == 1)) {
                    inserted.add(first.id());
                    continue;
                }
                // 동시 삽입으로 생긴 행이나, 다음 중복 후보에서 좌표를 보완할 행은 지금 읽는다.
                place = placeRepository.findByGooglePlaceId(first.id()).orElseThrow();
                byGoogleId.put(first.id(), place);
            }
            for (GooglePlacesNearbySearchResponse.Place raw : group) {
                repairLocationIfMissing(place, raw);
            }
        }
        if (!inserted.isEmpty()) {
            placeRepository.findByGooglePlaceIdIn(inserted)
                    .forEach(place -> byGoogleId.put(place.getGooglePlaceId(), place));
        }
        return rawCandidates.stream().map(raw -> byGoogleId.get(raw.id())).toList();
    }

    private boolean insertAllNew(List<GooglePlacesNearbySearchResponse.Place> sorted, LocalDateTime now) {
        String sql = "INSERT INTO places (google_place_id,name,category,rating,user_rating_count,price_level,"
                + "latitude,longitude,address,last_synced_at) VALUES "
                + String.join(",", Collections.nCopies(sorted.size(), "(?,?,?,?,?,?,?,?,?,?)"))
                + " ON CONFLICT DO NOTHING";
        return entityManager.unwrap(Session.class).doReturningWork(connection -> {
            Savepoint beforeInsert = connection.setSavepoint();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                for (GooglePlacesNearbySearchResponse.Place raw : sorted) {
                    statement.setString(index++, raw.id());
                    statement.setString(index++, raw.displayName() == null ? null : raw.displayName().text());
                    statement.setString(index++, raw.types() == null || raw.types().isEmpty() ? null : raw.types().getFirst());
                    statement.setObject(index++, raw.rating());
                    statement.setObject(index++, raw.userRatingCount());
                    statement.setString(index++, raw.priceLevel());
                    statement.setObject(index++, raw.location() == null ? null : raw.location().latitude());
                    statement.setObject(index++, raw.location() == null ? null : raw.location().longitude());
                    statement.setString(index++, raw.formattedAddress());
                    statement.setObject(index++, now);
                }
                RequestDbQueryMetrics.recordJdbcStatements(1);
                boolean allNew;
                RequestIoTime timer = RequestIoTime.startDirectJdbc();
                try {
                    allNew = statement.executeUpdate() == sorted.size();
                } finally {
                    timer.jdbcExecuteStatementEnd();
                }
                if (!allNew) {
                    // 동시 삽입된 행의 좌표도 복구해야 한다. 먼저 이 묶음의 잠금을 풀어 기존 ID순을 지킨다.
                    connection.rollback(beforeInsert);
                }
                connection.releaseSavepoint(beforeInsert);
                return allNew;
            }
        });
    }

    private boolean insertIfAbsent(GooglePlacesNearbySearchResponse.Place raw, LocalDateTime now) {
        String category = raw.types() != null && !raw.types().isEmpty() ? raw.types().getFirst() : null;
        String name = raw.displayName() == null ? null : raw.displayName().text();
        Double lat = raw.location() == null ? null : raw.location().latitude();
        Double lng = raw.location() == null ? null : raw.location().longitude();
        return placeRepository.insertIfAbsent(raw.id(), name, category, raw.rating(), raw.userRatingCount(),
                raw.priceLevel(), lat, lng, raw.formattedAddress(), now) == 1;
    }

    private void repairLocationIfMissing(Place place, GooglePlacesNearbySearchResponse.Place raw) {
        if ((place.getLatitude() == null || place.getLongitude() == null) && raw.location() != null
                && Math.abs(raw.location().latitude()) <= 90 && Math.abs(raw.location().longitude()) <= 180) {
            placeRepository.repairMissingLocation(place.getId(), raw.location().latitude(),
                    raw.location().longitude(), raw.formattedAddress());
            entityManager.refresh(place);
        }
    }
}
