-- 운영 규모와 비슷한 작은 데이터: 사용자 20명 × 장소 40개(영상 4개).
INSERT INTO users (id, provider, provider_user_id, nickname, created_at)
SELECT u, 'google', 'gc-'||u, 'gc-'||u, now() FROM generate_series(1,20) u;
INSERT INTO processing_jobs (id,user_id,source_url,source_platform,status,retry_count,created_at,updated_at,analysis_status,analysis_finished_at)
SELECT j, (j-1)/4+1, 'https://www.youtube.com/watch?v=gc'||j, 'YOUTUBE','DONE',0,now(),now(),'DONE',now()
FROM generate_series(1,80) j;
INSERT INTO saved_places (processing_job_id,user_id,place_name,region,category,latitude,longitude,source_url,source_platform,created_at,video_notes)
SELECT j, (j-1)/4+1, 'gc-'||((j-1)/4+1)||'-place-'||p, 'Seoul','cafe',37.5,127.0,
'https://www.youtube.com/watch?v=gc'||j, 'YOUTUBE', now(), repeat('Synthetic travel note. ',8)
FROM generate_series(1,80) j CROSS JOIN generate_series(1,10) p;
ANALYZE;
