INSERT INTO users (id, provider, provider_user_id, nickname, created_at)
SELECT u, 'google', 'capacity-'||u, 'capacity-'||u, now() FROM generate_series(1,160) u;
INSERT INTO processing_jobs (id,user_id,source_url,source_platform,status,retry_count,created_at,updated_at,analysis_status,analysis_finished_at)
SELECT j, (j-1)/100+1, 'https://www.youtube.com/watch?v=capacity'||j, 'YOUTUBE','DONE',0,now(),now(),'DONE',now()
FROM generate_series(1,16000) j;
INSERT INTO saved_places (processing_job_id,user_id,place_name,region,category,latitude,longitude,source_url,source_platform,created_at,video_notes)
SELECT j, (j-1)/100+1, 'capacity-'||((j-1)/100+1)||'-place-'||p, 'Seoul','cafe',37.5,127.0,
'https://www.youtube.com/watch?v=capacity'||j, 'YOUTUBE', now(), repeat('Synthetic travel note. ',8)
FROM generate_series(1,16000) j CROSS JOIN generate_series(1,10) p;
ANALYZE;
