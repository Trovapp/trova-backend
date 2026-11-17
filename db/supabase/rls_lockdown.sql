-- 목적(#454): public 테이블에 RLS를 켜고 Supabase API 역할의 직접 권한을 회수한다.
-- 실행: Supabase SQL 편집기에서 postgres 역할로 전체 실행하거나,
--       postgres 연결로 psql -X -v ON_ERROR_STOP=1 -f db/supabase/rls_lockdown.sql
-- 백엔드 영향: 테이블 소유자 postgres는 FORCE 없는 RLS를 우회한다.
--              anon/authenticated 권한만 회수하므로 postgres의 JDBC 접근은 유지된다.
-- 되돌리기: ensure_rls 이벤트 트리거를 먼저 DROP하고 public.rls_auto_enable()을 DROP한다.
--           대상 테이블마다 ALTER TABLE ... DISABLE ROW LEVEL SECURITY를 실행하고,
--           적용 전 기록을 기준으로 테이블/시퀀스/함수 GRANT와 postgres의
--           ALTER DEFAULT PRIVILEGES ... GRANT를 복원한다(무조건 ALL 부여하지 않음).
-- 여러 번 실행 가능. 한 트랜잭션으로 적용하며 정책은 만들지 않고 FORCE도 설정하지 않는다.
BEGIN;

DO $rls$
DECLARE
    target record;
BEGIN
    FOR target IN
        SELECT schemaname, tablename
        FROM pg_catalog.pg_tables
        WHERE schemaname = 'public'
    LOOP
        EXECUTE pg_catalog.format(
            'ALTER TABLE %I.%I ENABLE ROW LEVEL SECURITY',
            target.schemaname, target.tablename
        );
    END LOOP;
END;
$rls$;

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM anon, authenticated;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM anon, authenticated;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM anon, authenticated;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    REVOKE ALL ON TABLES FROM anon, authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    REVOKE ALL ON SEQUENCES FROM anon, authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    REVOKE ALL ON FUNCTIONS FROM anon, authenticated;

CREATE OR REPLACE FUNCTION public.rls_auto_enable()
RETURNS event_trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $rls$
DECLARE
    command record;
BEGIN
    FOR command IN
        -- CREATE TABLE AS / SELECT INTO도 테이블을 만들므로 같은 방식으로 보호한다.
        SELECT n.nspname, c.relname
        FROM pg_catalog.pg_event_trigger_ddl_commands() AS ddl
        JOIN pg_catalog.pg_class AS c ON c.oid = ddl.objid
        JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
        WHERE ddl.command_tag IN ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
            AND ddl.schema_name = 'public'
            AND ddl.classid = 'pg_catalog.pg_class'::pg_catalog.regclass
            AND n.nspname = 'public'
            AND c.relkind IN ('r', 'p')
    LOOP
        -- 식별자는 카탈로그에서 읽고 각각 인용한다(이름에 공백·따옴표가 있어도 안전).
        EXECUTE pg_catalog.format(
            'ALTER TABLE %I.%I ENABLE ROW LEVEL SECURITY', command.nspname, command.relname
        );
    END LOOP;
END;
$rls$;

ALTER FUNCTION public.rls_auto_enable() OWNER TO postgres;
-- 함수의 기본 EXECUTE는 PUBLIC에도 있으므로 이 보조 함수는 그 경로까지 회수한다.
REVOKE ALL ON FUNCTION public.rls_auto_enable() FROM PUBLIC, anon, authenticated;

DROP EVENT TRIGGER IF EXISTS ensure_rls;
CREATE EVENT TRIGGER ensure_rls
    ON ddl_command_end
    WHEN TAG IN ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
    EXECUTE FUNCTION public.rls_auto_enable();

COMMIT;
