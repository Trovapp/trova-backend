import os
import tempfile
import unittest

from prod_readonly_psql import check_sql, pg_env, wrap


class CheckSqlTest(unittest.TestCase):

    def test_조회만_있으면_통과한다(self):
        self.assertIsNone(check_sql("select count(*) from users;\nselect max(id) from trips"))

    def test_트랜잭션을_끝내는_문장은_거부한다(self):
        for sql in ("select 1; commit; delete from users", "END;", "rollback", "begin; select 1",
                    "start transaction read write", "savepoint a"):
            self.assertIsNotNone(check_sql(sql), sql)

    def test_읽기_전용을_끌_수_있는_설정_변경은_거부한다(self):
        for sql in ("set transaction read write", "SET default_transaction_read_only = off", "reset all"):
            self.assertIsNotNone(check_sql(sql), sql)

    def test_psql_메타_명령과_달러_따옴표는_거부한다(self):
        self.assertIsNotNone(check_sql("\\c other"))
        self.assertIsNotNone(check_sql("do $$ begin null; end $$"))

    def test_문자열이나_주석_안의_글자로는_막지_않는다(self):
        self.assertIsNone(check_sql("select 'weekend' as w -- commit 아님\nfrom users where nickname <> 'end'"))
        self.assertIsNone(check_sql('select "end" from t /* rollback */'))

    def test_빈_입력은_거부한다(self):
        self.assertIsNotNone(check_sql("  \n"))


class WrapTest(unittest.TestCase):

    def test_읽기_전용_트랜잭션으로_감싸고_확인한_뒤_롤백한다(self):
        wrapped = wrap("select 1")
        lines = wrapped.strip().splitlines()
        self.assertEqual("begin transaction read only;", lines[0])
        self.assertIn("current_setting('transaction_read_only') <> 'on'", wrapped)
        self.assertIn("select 1;", wrapped)
        self.assertEqual("rollback;", lines[-1])
        self.assertLess(wrapped.index("transaction_read_only"), wrapped.index("select 1;"))


class PgEnvTest(unittest.TestCase):

    def test_스프링_JDBC_주소를_psql_환경으로_바꾼다(self):
        with tempfile.NamedTemporaryFile("w", suffix=".env", delete=False) as f:
            f.write("# 주석\nSPRING_DATASOURCE_URL=jdbc:postgresql://db.example.com:6543/postgres?sslmode=require\n")
            f.write("SPRING_DATASOURCE_USERNAME=reader\nSPRING_DATASOURCE_PASSWORD='pw'\nOTHER_KEY=x\n")
            path = f.name
        try:
            env = pg_env(path)
        finally:
            os.unlink(path)
        self.assertEqual({"PGHOST": "db.example.com", "PGPORT": "6543", "PGDATABASE": "postgres",
                          "PGUSER": "reader", "PGPASSWORD": "pw", "PGSSLMODE": "require"}, env)


if __name__ == "__main__":
    unittest.main()
