-- #537: 실내외 태그의 30일 유효기간. 이 레포는 Flyway 대신 Hibernate ddl-auto:update를 쓴다.
-- 배포 시 엔티티 매핑으로 추가되는 nullable 컬럼과 동일한 DDL이며, 명시 적용이 필요한 환경을 위한 참고다.
-- 기존 태그의 생성 시각은 알 수 없으므로 backfill하지 않는다. 다음 실내 필터 요청에서 한 번 갱신한다.
-- 이 작업에서는 개발/운영 DB에 실행하지 않았다.
ALTER TABLE public.places ADD COLUMN IF NOT EXISTS space_tagged_at timestamp without time zone;
