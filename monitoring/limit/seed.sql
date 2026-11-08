-- "서비스가 커졌을 때" 가정 데이터(#342). 운영(11-05: 사용자 12명, 장소 283, 찜·여행 거의 없음)보다 크게 잡는다.
-- 사용자 50명 × 저장 장소 40개(영상 4개), 찜 10개(폴더 2개), 여행 2개(3일 × 하루 4곳), 안 읽은 알림 3개.
INSERT INTO users (id, provider, provider_user_id, nickname, created_at)
SELECT u, 'google', 'limit-'||u, 'limit-'||u, now() FROM generate_series(1,50) u;
INSERT INTO processing_jobs (id,user_id,source_url,source_platform,status,retry_count,created_at,updated_at,analysis_status,analysis_finished_at)
SELECT j, (j-1)/4+1, 'https://www.youtube.com/watch?v=limit'||j, 'YOUTUBE','DONE',0,now(),now(),'DONE',now()
FROM generate_series(1,200) j;
INSERT INTO saved_places (processing_job_id,user_id,place_name,region,category,latitude,longitude,source_url,source_platform,created_at,video_notes)
SELECT j, (j-1)/4+1, 'limit-'||((j-1)/4+1)||'-place-'||p, 'Seoul','cafe',37.5+p*0.001,127.0+j*0.0001,
'https://www.youtube.com/watch?v=limit'||j, 'YOUTUBE', now(), repeat('Synthetic travel note. ',8)
FROM generate_series(1,200) j CROSS JOIN generate_series(1,10) p;
INSERT INTO places (id,google_place_id,name,category,rating,user_rating_count,latitude,longitude,address,last_synced_at)
SELECT p, 'limit-g-'||p, 'limit-catalog-'||p, 'cafe', 4.2, 100, 33.4+p*0.0001, 126.5, 'Jeju', now() FROM generate_series(1,500) p;
INSERT INTO bookmark_folders (id,user_id,name,color,created_at)
SELECT f, (f-1)/2+1, 'folder-'||f, '#ff0000', now() FROM generate_series(1,100) f;
INSERT INTO bookmarks (user_id,place_id,folder_id,created_at)
SELECT u, ((u-1)*10+b) % 500 + 1, CASE WHEN b % 3 = 0 THEN NULL ELSE (u-1)*2 + 1 + b % 2 END, now()
FROM generate_series(1,50) u CROSS JOIN generate_series(1,10) b;
INSERT INTO trips (id,user_id,title,start_date,end_date,created_at,updated_at)
SELECT t, (t-1)/2+1, 'trip-'||t, current_date+10, current_date+12, now(), now() FROM generate_series(1,100) t;
INSERT INTO itineraries (id,trip_id,day_number,date)
SELECT i, (i-1)/3+1, (i-1)%3+1, current_date+10+(i-1)%3 FROM generate_series(1,300) i;
INSERT INTO trip_places (id,itinerary_id,place_name,region,category,latitude,longitude,visit_order,source,created_at)
SELECT k, (k-1)/4+1, 'stop-'||k, 'Jeju', 'cafe', 33.4+k*0.0001, 126.5, (k-1)%4, 'NORMAL', now() FROM generate_series(1,1200) k;
-- 알림: 사용자 u의 첫 여행(2u-1) 1일차(itinerary 3*(2u-1)-2)의 장소들
INSERT INTO notifications (user_id,itinerary_id,trip_place_id,title,body,precipitation_prob,is_read,created_at)
SELECT u, 3*(2*u-1)-2, (3*(2*u-1)-3)*4 + n, 'rain-'||n, 'umbrella', 0.7, false, now()
FROM generate_series(1,50) u CROSS JOIN generate_series(1,3) n;
ANALYZE;
