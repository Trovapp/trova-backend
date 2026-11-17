-- #454 읽기 전용 점검. 적용 전·후 결과를 비교한다. 이 파일에는 SELECT만 둔다.
-- 대장 실행 예:
-- PROD_DB_ENV_FILE=<운영 DB 환경 파일> python3 monitoring/prod_readonly_psql.py < db/supabase/rls_check.sql
-- 기대: 모든 public 테이블 relrowsecurity=true, relforcerowsecurity=false,
--       API 역할의 직접 권한 0, ensure_rls 활성 및 postgres 소유 SECURITY DEFINER.
-- 유효 권한은 PUBLIC·역할 상속도 포함한다. 특히 일반 함수의 PUBLIC EXECUTE는
-- 역할별 REVOKE만으로 사라지지 않으며, 스키마별 기본 권한 회수도 전역 부여를 지우지 않는다.

SELECT n.nspname AS schema_name,
       c.relname AS table_name,
       pg_catalog.pg_get_userbyid(c.relowner) AS owner,
       c.relrowsecurity,
       c.relforcerowsecurity,
       (SELECT count(*) FROM pg_catalog.pg_policy AS p WHERE p.polrelid = c.oid) AS policy_count
FROM pg_catalog.pg_class AS c
JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
ORDER BY c.relname;

-- 기존 실측의 7종(SELECT/INSERT/UPDATE/DELETE/TRUNCATE/REFERENCES/TRIGGER) × 2역할.
-- direct_privilege_count는 ACL 직접 부여, effective_privilege_count는 실제 유효 권한 수.
SELECT c.relname AS table_name,
       r.rolname AS role_name,
       (SELECT count(*)
        FROM pg_catalog.aclexplode(c.relacl) AS a
        WHERE a.grantee = r.oid) AS direct_privilege_count,
       (SELECT count(*)
        FROM (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE'),
                     ('TRUNCATE'), ('REFERENCES'), ('TRIGGER')) AS p(privilege)
        WHERE pg_catalog.has_table_privilege(r.oid, c.oid, p.privilege)) AS effective_privilege_count
FROM pg_catalog.pg_class AS c
JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
CROSS JOIN pg_catalog.pg_roles AS r
WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
    AND r.rolname IN ('anon', 'authenticated')
ORDER BY c.relname, r.rolname;

SELECT c.relname AS sequence_name,
       r.rolname AS role_name,
       (SELECT count(*) FROM pg_catalog.aclexplode(c.relacl) AS a
        WHERE a.grantee = r.oid) AS direct_privilege_count,
       (SELECT count(*) FROM (VALUES ('USAGE'), ('SELECT'), ('UPDATE')) AS p(privilege)
        WHERE pg_catalog.has_sequence_privilege(r.oid, c.oid, p.privilege)) AS effective_privilege_count
FROM pg_catalog.pg_class AS c
JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
CROSS JOIN pg_catalog.pg_roles AS r
WHERE n.nspname = 'public' AND c.relkind = 'S'
    AND r.rolname IN ('anon', 'authenticated')
ORDER BY c.relname, r.rolname;

SELECT p.oid::pg_catalog.regprocedure AS function_name,
       r.rolname AS role_name,
       (SELECT count(*) FROM pg_catalog.aclexplode(p.proacl) AS a
        WHERE a.grantee = r.oid) AS direct_privilege_count,
       pg_catalog.has_function_privilege(r.oid, p.oid, 'EXECUTE') AS effective_execute
FROM pg_catalog.pg_proc AS p
JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
CROSS JOIN pg_catalog.pg_roles AS r
WHERE n.nspname = 'public' AND p.prokind <> 'p'
    AND r.rolname IN ('anon', 'authenticated')
ORDER BY p.oid::pg_catalog.regprocedure::text, r.rolname;

-- 트리거가 없어도 1행을 반환한다. evtenabled='O'는 일반 활성 상태, 'D'는 비활성.
SELECT e.evtname,
       e.evtname IS NOT NULL AS trigger_exists,
       e.evtevent,
       e.evtenabled,
       e.evttags,
       e.evtfoid::pg_catalog.regprocedure AS function_name,
       pg_catalog.pg_get_userbyid(p.proowner) AS function_owner,
       p.prosecdef AS security_definer,
       p.proconfig AS function_settings
FROM (VALUES ('ensure_rls')) AS expected(name)
LEFT JOIN pg_catalog.pg_event_trigger AS e ON e.evtname = expected.name
LEFT JOIN pg_catalog.pg_proc AS p ON p.oid = e.evtfoid;

-- postgres의 public·전역 기본 ACL 요약.
-- 행이 없으면 Postgres 표준 기본값을 사용하며, 권한이 없다는 뜻은 아니다.
-- objtype: r=테이블, S=시퀀스, f=함수. PUBLIC·다른 역할의 부여도 표시한다.
SELECT pg_catalog.pg_get_userbyid(d.defaclrole) AS creator_role,
       coalesce(n.nspname, '(global)') AS schema_name,
       d.defaclobjtype AS object_type,
       coalesce(r.rolname, 'PUBLIC') AS grantee,
       count(a.privilege_type) AS privilege_count,
       string_agg(a.privilege_type, ', ' ORDER BY a.privilege_type) AS privileges
FROM pg_catalog.pg_default_acl AS d
LEFT JOIN pg_catalog.pg_namespace AS n ON n.oid = d.defaclnamespace
LEFT JOIN LATERAL pg_catalog.aclexplode(d.defaclacl) AS a ON true
LEFT JOIN pg_catalog.pg_roles AS r ON r.oid = a.grantee
WHERE d.defaclrole = (SELECT oid FROM pg_catalog.pg_roles WHERE rolname = 'postgres')
    AND (n.nspname = 'public' OR d.defaclnamespace = 0)
    AND d.defaclobjtype IN ('r', 'S', 'f')
GROUP BY d.defaclrole, d.defaclnamespace, n.nspname, d.defaclobjtype, a.grantee, r.rolname
ORDER BY schema_name, object_type, grantee;
