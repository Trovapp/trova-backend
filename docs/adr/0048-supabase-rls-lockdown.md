# 0048. Supabase public 테이블 RLS 활성화와 API 역할 권한 회수

- 상태: 확정 · 날짜: 2026-11-17 · 근거: 이슈 #454, 사용자 지시와 운영 읽기 전용 조회 결과(사용자 제공)

## 결정

`public`의 모든 일반 테이블에 RLS를 켜고 정책은 만들지 않는다. `FORCE ROW LEVEL SECURITY`는 설정하지 않는다. 백엔드는 테이블 소유자 `postgres`로 JDBC에 접속하므로 RLS를 우회하며, Java 코드는 변경하지 않는다.

`anon`·`authenticated`의 기존 테이블·시퀀스·함수 권한을 회수한다. `postgres`가 앞으로 `public`에 만드는 같은 객체의 기본 권한도 두 역할에서 회수한다. `ensure_rls` 이벤트 트리거와 `public.rls_auto_enable()` 함수로 `CREATE TABLE`·`CREATE TABLE AS`·`SELECT INTO` 시 새 public 테이블에도 RLS를 켠다. 함수는 `postgres` 소유 `SECURITY DEFINER`, 고정 `search_path=pg_catalog`로 만들고, 이 함수의 EXECUTE는 `PUBLIC`·`anon`·`authenticated` 모두에서 회수한다.

적용 파일은 [rls_lockdown.sql](../../db/supabase/rls_lockdown.sql), 읽기 전용 점검은 [rls_check.sql](../../db/supabase/rls_check.sql)이다. 적용은 대장이 개발 DB부터 postgres 역할로 수행한다. 적용 SQL은 한 트랜잭션이며 재실행할 수 있다. 점검은 운영에서 `monitoring/prod_readonly_psql.py`에 표준 입력으로 넘긴다. 되돌릴 때는 이벤트 트리거·함수를 제거하고 RLS를 끈 뒤 적용 전 기록에 따라 기존·기본 권한을 복원한다.

## 이유

바이브코딩 체크리스트 점검 중 운영 읽기 전용 조회로 public 테이블 22개와 테이블별 권한 7종 × API 역할 2개가 확인됐다(이번 작성 세션에서 재조회하지 않은 사용자 제공 실측). 앱은 백엔드를 통해 데이터에 접근하므로 Supabase API 역할에 테이블 직접 접근을 허용할 필요가 없다. RLS와 권한 회수를 함께 적용하고, 새 테이블도 자동 보호해 테이블 추가 시 빠뜨리는 일을 줄인다.

RLS는 정책이 없으면 일반 역할의 행 접근을 기본 거부하지만 테이블 소유자는 FORCE가 없으면 우회한다. 기존 정책을 삭제하거나 FORCE 설정을 해제하는 SQL은 포함하지 않으므로 점검에서 정책 수·소유자·FORCE 여부도 확인한다. 백엔드 역할이나 테이블 소유자가 달라지면 영향 판단을 다시 해야 한다.

역할별 REVOKE는 `PUBLIC`·역할 상속을 통한 권한까지 없애지 않는다. 특히 일반 함수의 `PUBLIC EXECUTE`는 남을 수 있으며 RLS는 함수 실행 자체를 막지 않는다. 스키마별 기본 권한 회수도 전역 기본 부여를 취소하지 않고 다른 생성 역할의 기본 권한을 바꾸지 않는다. 그래서 점검은 직접 부여와 유효 권한을 구분하고 전역 기본 ACL도 표시한다. 남은 유효 권한이나 SECURITY DEFINER 함수가 있으면 노출 범위를 확인하고 별도 권한 회수를 검토한다.

## 비교한 대안

- 정책 기반 RLS로 앱 직접 접속: 사용자별 정책·인증 연계·정책 검증이 필요하다. 현재 백엔드 소유권 검사 경로를 쓰는 구조에서는 직접 접속을 새로 열 이유가 없다.
- Data API만 끄기: API 경로는 닫을 수 있으나 DB의 권한과 RLS 누락은 남고, API를 다시 켜면 같은 상태가 드러난다. DB에서 접근을 제한하는 이번 결정을 대신하지 않는다.
- 그대로 두기: 변경은 없지만 현재 필요하지 않은 API 역할의 권한과 새 테이블의 보호 누락이 유지된다.

## 다시 볼 조건

앱이 Supabase에 직접 접속하게 되면 사용자·소유권별 RLS 정책과 필요한 최소 GRANT를 설계하고 검증한다. 백엔드 접속 역할·테이블 소유자·DDL 생성 역할을 바꾸거나 public에 새로운 RPC/SECURITY DEFINER 함수를 추가할 때도 우회 범위와 기본 권한을 다시 확인한다.
