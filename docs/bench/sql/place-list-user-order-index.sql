-- 검토 후 지정 DB에서만 실행. psql autocommit 사용, BEGIN 안에서 실행하지 않는다.
-- 운영 적용 전 동일 이름의 기존 인덱스 정의와 유효성을 확인한다.
SELECT current_database(), current_user;
SELECT indexname, indexdef FROM pg_indexes
WHERE schemaname = 'public' AND tablename = 'saved_places';

-- IF NOT EXISTS로 잘못된 정의/invalid 인덱스를 숨기지 않는다.
CREATE INDEX CONCURRENTLY idx_saved_places_user_created_id
ON public.saved_places (user_id, created_at, id);

SELECT i.indisvalid, i.indisready, pg_get_indexdef(i.indexrelid)
FROM pg_index i
WHERE i.indexrelid = 'public.idx_saved_places_user_created_id'::regclass;

-- 롤백이 필요할 때 별도로 실행:
-- DROP INDEX CONCURRENTLY public.idx_saved_places_user_created_id;
