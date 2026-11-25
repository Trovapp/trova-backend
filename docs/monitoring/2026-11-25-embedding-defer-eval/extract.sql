-- T45 반사실 실험: 운영 읽기 전용, 외부 API 0회. 원본 후보/여행은 삭제돼 실제 응답 재현이 아님.
-- 매니저: psql 변수 test_user_1/test_user_2(확인된 테스트 계정만), targets_json(targets.json 전체)을 주입.
-- 첫 SELECT만 data/candidates.csv로 CSV 저장, 둘째 SELECT는 data/schema.csv로 따로 저장.
-- 모든 시간은 JVM TZ=Asia/Seoul 기준 timestamp without time zone (매니저 확인 2026-11-25).
-- 원본 일치: 공백/구두점 제거 이름 부분일치 + 입력 지역 중심 60km 이내. 정규화 정확일치→이름 길이차→최단거리 1개 선택.
-- 근사 좌표는 동명이장소 배제용이고, 후보 반경 2km는 매칭된 카탈로그 원본 좌표에서 계산.
-- 서비스의 후보 조회는 Google Places API이므로 재사용 가능한 후보 SQL은 없음.
-- 이 SQL은 현재 카탈로그의 첫 category를 GoogleTypeMapper.defaultTypeFor와 같은 군으로 묶어
-- primary를 근사하고, 인기도 상위20개로 잘라 Google 입력을 근사함(전체 Google types/응답순서 소실).
-- 원본 type 무매핑이면 전체 검색. primary 3개 미만이면 전체 상위20개 보충(서비스 조건).
-- 뒤의 정렬/개인화는 실제 서비스로 오프라인 재생. 비일치 표본을 숨기지 않도록 원본 미매칭도 출력.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
WITH allowed_users(slot, user_id) AS (
 VALUES (1, :'test_user_1'::bigint), (2, :'test_user_2'::bigint)
), inputs AS (
 SELECT r.*, a.user_id
 FROM jsonb_to_recordset(:'targets_json'::jsonb) AS r(
   request_alias text, user_slot integer, target_name text, region_alias text,
   approx_lat double precision, approx_lon double precision, requested_at_kst timestamp,
   visible_candidate_names jsonb)
 JOIN allowed_users a ON a.slot=r.user_slot
 WHERE requested_at_kst >= timestamp '2026-11-25 18:18:00'
   AND requested_at_kst < timestamp '2026-11-25 19:05:01'
), catalog AS (
 SELECT p.*,
   CASE
    WHEN p.category IN ('restaurant','맛집','음식점','식당') OR p.category LIKE '%\_restaurant' ESCAPE '\' THEN 'restaurant'
    WHEN p.category ~ '(cafe|coffee|bakery|breakfast|brunch|sandwich|dessert|tea_house)' OR p.category IN ('카페','커피') THEN 'cafe'
    WHEN p.category IN ('lodging','hotel','guest_house','motel','hostel','숙소','호텔') OR p.category LIKE '%\_hotel' ESCAPE '\' THEN 'lodging'
    WHEN p.category IN ('shopping','shopping_mall','department_store','market','쇼핑') OR p.category LIKE '%\_store' ESCAPE '\' THEN 'shopping_mall'
    WHEN p.category IN ('tourist_attraction','attraction','park','national_park','state_park','museum','temple','amusement_park','zoo','aquarium','art_gallery','historical_landmark','historical_place','cultural_landmark','monument','beach','botanical_garden','박물관','미술관','공원','관광') OR p.category LIKE '%\_temple' ESCAPE '\' THEN 'tourist_attraction'
    ELSE NULL END AS search_type,
   greatest(coalesce(p.rating,0)*ln(coalesce(p.user_rating_count,0)+1),1) AS popularity_proxy
 FROM places p WHERE p.latitude IS NOT NULL AND p.longitude IS NOT NULL
), targets AS (
 SELECT r.*, t.id AS target_id, t.category AS target_category, t.search_type,
        t.latitude AS target_lat, t.longitude AS target_lon, t.matches AS target_match_count,
        regexp_replace(lower(t.name),'[[:space:][:punct:]]','','g')=regexp_replace(lower(r.target_name),'[[:space:][:punct:]]','','g') AS target_exact_name_match
 FROM inputs r LEFT JOIN LATERAL (
  SELECT p.*, count(*) OVER () AS matches
  FROM catalog p
  WHERE (
    regexp_replace(lower(p.name),'[[:space:][:punct:]]','','g') LIKE '%'||regexp_replace(lower(r.target_name),'[[:space:][:punct:]]','','g')||'%'
    OR regexp_replace(lower(r.target_name),'[[:space:][:punct:]]','','g') LIKE '%'||regexp_replace(lower(p.name),'[[:space:][:punct:]]','','g')||'%'
  ) AND length(p.name)>=2
    AND 6371*2*asin(sqrt(least(1.0,power(sin(radians(p.latitude-r.approx_lat)/2),2)+cos(radians(r.approx_lat))*cos(radians(p.latitude))*power(sin(radians(p.longitude-r.approx_lon)/2),2))))<=60
  ORDER BY (regexp_replace(lower(p.name),'[[:space:][:punct:]]','','g')=regexp_replace(lower(r.target_name),'[[:space:][:punct:]]','','g')) DESC,
           abs(length(regexp_replace(p.name,'[[:space:][:punct:]]','','g'))-length(regexp_replace(r.target_name,'[[:space:][:punct:]]','','g'))),
           power(p.latitude-r.approx_lat,2)+power(p.longitude-r.approx_lon,2),p.id
  LIMIT 1
 ) t ON true
), nearby AS (
 SELECT t.request_alias, p.id, p.category, p.rating, p.user_rating_count,p.embedding,p.last_synced_at,p.name,
        p.popularity_proxy, d.km AS distance_target_km,
        t.search_type IS NULL OR p.search_type=t.search_type AS is_primary
 FROM targets t JOIN catalog p ON p.id<>t.target_id
 CROSS JOIN LATERAL (SELECT 6371*2*asin(sqrt(least(1.0,power(sin(radians(p.latitude-t.target_lat)/2),2)+cos(radians(t.target_lat))*cos(radians(p.latitude))*power(sin(radians(p.longitude-t.target_lon)/2),2)))) AS km) d
 WHERE d.km<=2
   AND NOT (regexp_replace(lower(p.name),'[[:space:]]','','g') LIKE '%'||regexp_replace(lower(t.target_name),'[[:space:]]','','g') AND d.km<=0.3)
), ordered AS (
 SELECT n.*, row_number() OVER (PARTITION BY request_alias,is_primary ORDER BY popularity_proxy DESC,id) AS group_order,
        row_number() OVER (PARTITION BY request_alias ORDER BY popularity_proxy DESC,id) AS all_order,
        count(*) FILTER (WHERE is_primary) OVER (PARTITION BY request_alias) AS primary_count
 FROM nearby n
), selected AS (
 SELECT n.*, row_number() OVER (PARTITION BY request_alias ORDER BY is_primary DESC,popularity_proxy DESC,id)-1 AS input_order
 FROM ordered n
 WHERE ((is_primary AND group_order<=20) OR (primary_count<3 AND all_order<=20))
 -- 이번 원본은 한 곳만 있던 여행/필터 없음/저녁. 다른 일정 장소·아침·실내·거리 필터 없음.
 AND (category IS NULL OR category NOT IN ('lodging','hotel','motel','hostel','guest_house','resort_hotel','extended_stay_hotel','subway_station','train_station','transit_station','bus_station','light_rail_station','airport','university','school','primary_school','secondary_school','parking','gas_station'))
)
SELECT t.request_alias, 'U'||t.user_slot AS user_alias, t.requested_at_kst,t.target_category,t.search_type,
       t.target_match_count,t.target_exact_name_match, n.input_order,
       CASE WHEN n.id IS NOT NULL THEN 'C'||lpad((n.input_order+1)::text,3,'0') END AS candidate_alias,
       n.is_primary AS "primary", n.category,n.rating,n.user_rating_count,n.distance_target_km,
       NULL::double precision AS distance_next_km,
       n.embedding IS NOT NULL AS embedded_now,n.last_synced_at,
       CASE WHEN n.id IS NULL THEN NULL ELSE NOT (n.last_synced_at>=timestamp '2026-11-25 18:18:00' AND n.last_synced_at<timestamp '2026-11-25 19:05:01') END AS embedded_before,
       'window_last_synced_proxy' AS mask_source,
       coalesce(sim.similarities,'[]'::jsonb) AS similarities,
       sig.signal_count,sig.embedded_signal_count,coalesce(sig.categories,'[]'::jsonb) AS signal_categories,
       coalesce(vis.visible_indices,'[]'::jsonb) AS visible_indices,
       jsonb_array_length(t.visible_candidate_names) AS visible_count
FROM targets t LEFT JOIN selected n ON n.request_alias=t.request_alias
LEFT JOIN LATERAL (
 SELECT jsonb_agg(x.similarity ORDER BY x.distance) AS similarities FROM (
  SELECT 1-(sp.embedding <=> CAST(CAST(n.embedding AS text) AS vector)) AS similarity,
         sp.embedding <=> CAST(CAST(n.embedding AS text) AS vector) AS distance
  FROM user_preference_signals s JOIN places sp ON sp.id=s.place_id
  WHERE s.user_id=t.user_id AND sp.embedding IS NOT NULL AND n.embedding IS NOT NULL
    AND s.created_at<=t.requested_at_kst
  ORDER BY sp.embedding <=> CAST(CAST(n.embedding AS text) AS vector) LIMIT 5
 ) x
) sim ON true
LEFT JOIN LATERAL (
 SELECT count(*) AS signal_count,count(*) FILTER (WHERE sp.embedding IS NOT NULL) AS embedded_signal_count,
        jsonb_agg(sp.category ORDER BY s.id) FILTER (WHERE sp.embedding IS NOT NULL) AS categories
 FROM user_preference_signals s JOIN places sp ON sp.id=s.place_id
 WHERE s.user_id=t.user_id AND s.created_at<=t.requested_at_kst
) sig ON true
LEFT JOIN LATERAL (
 SELECT jsonb_agg(v.ordinality ORDER BY v.ordinality) AS visible_indices
 FROM jsonb_array_elements_text(t.visible_candidate_names) WITH ORDINALITY AS v(name,ordinality)
 WHERE regexp_replace(lower(v.name),'[[:space:][:punct:]]','','g')=regexp_replace(lower(n.name),'[[:space:][:punct:]]','','g')
) vis ON true
ORDER BY t.request_alias,n.input_order;

SELECT table_name,column_name,data_type FROM information_schema.columns
WHERE table_schema='public' AND table_name IN ('places','user_preference_signals')
 AND (column_name LIKE '%created%' OR column_name LIKE '%embedding%' OR column_name='last_synced_at')
ORDER BY table_name,ordinal_position;
COMMIT;
