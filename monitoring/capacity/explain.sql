EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT p.id,j.id,j.source_url,p.place_name,p.region,p.category,p.latitude,p.longitude,
p.source_url,p.title,p.source_platform,p.created_at,p.day_number,p.order_in_day,
p.phone,p.address,p.road_address,p.kakao_category_name,p.kakao_place_url,p.video_notes
FROM saved_places p JOIN processing_jobs j ON j.id=p.processing_job_id
WHERE p.user_id=1 ORDER BY p.created_at DESC,p.id DESC;
