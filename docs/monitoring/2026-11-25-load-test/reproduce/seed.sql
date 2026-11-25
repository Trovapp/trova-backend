\set ON_ERROR_STOP on
CREATE EXTENSION IF NOT EXISTS vector;
ALTER TABLE places ADD COLUMN IF NOT EXISTS embedding vector(768);
INSERT INTO users(id,provider,provider_user_id,nickname,created_at)
SELECT n,'google','isolated-loadtest-534-'||n,'부하테스트'||n,now() FROM generate_series(1,300)n;
INSERT INTO trips(id,user_id,title,start_date,end_date,created_at,updated_at,version)
SELECT n,n,'격리 부하 여행 '||n,'2026-11-25','2026-11-25',now(),now(),0 FROM generate_series(1,300)n;
INSERT INTO trip_members(trip_id,user_id,role,joined_at)
SELECT n,n,'OWNER',now() FROM generate_series(1,300)n;
-- 별도 공유 여행: 기본 개인 여행 조회에 멤버 수가 섞이지 않는다.
INSERT INTO trips(id,user_id,title,start_date,end_date,created_at,updated_at,version)
VALUES(301,1,'격리 공유 여행','2026-11-25','2026-11-25',now(),now(),0);
INSERT INTO trip_members(trip_id,user_id,role,joined_at)
SELECT 301,n,CASE WHEN n=1 THEN 'OWNER' ELSE 'EDITOR' END,now() FROM generate_series(1,10)n;
INSERT INTO itineraries(id,trip_id,day_number,date)
SELECT n,n,1,'2026-11-25' FROM generate_series(1,301)n;
-- 소스 장소 자체의 상세 외부 호출 없이 근처 검색을 수행할 수 있는 합성 좌표이다.
INSERT INTO trip_places(id,itinerary_id,place_name,region,category,latitude,longitude,visit_order,source,visit_start_time,visit_end_time,created_at,version)
SELECT (n-1)*4+p,n,'격리 합성 장소 '||p,'서울','tourist_attraction',37.5759+p*0.001,126.9768+p*0.001,p,'NORMAL',
('09:00'::time+(p-1)*interval '2 hours'),('10:00'::time+(p-1)*interval '2 hours'),now(),0
FROM generate_series(1,301)n CROSS JOIN generate_series(1,4)p;
-- 일일 한도 검증 전용: 추천할 빈 시간이 없어 외부 API 호출이 없다.
UPDATE trip_places SET visit_start_time=NULL,visit_end_time=NULL WHERE itinerary_id=299;
SELECT setval(pg_get_serial_sequence('users','id'),300);
SELECT setval(pg_get_serial_sequence('trips','id'),301);
SELECT setval(pg_get_serial_sequence('itineraries','id'),301);
SELECT setval(pg_get_serial_sequence('trip_places','id'),1204);
ANALYZE;
SELECT 'users',count(*) FROM users UNION ALL SELECT 'trips',count(*) FROM trips UNION ALL SELECT 'trip_places',count(*) FROM trip_places;
EXPLAIN (ANALYZE,BUFFERS) SELECT * FROM trip_places WHERE itinerary_id=1 ORDER BY visit_order;
