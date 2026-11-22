\getenv payment_db TICKETING_PAYMENT_POSTGRES_DB
\getenv payment_user TICKETING_PAYMENT_POSTGRES_USER
\set payment_password ''
\getenv payment_password TICKETING_PAYMENT_POSTGRES_PASSWORD

-- 비밀번호 리터럴이 들어간 문이 실패해도 서버 로그(log_min_error_statement 기본 ERROR)에 문장이 남지 않게 한다. 세션 한정 설정이라 되돌릴 필요가 없다
SET log_min_error_statement = panic;
SET log_statement = none;
SET log_min_duration_statement = -1;

-- 기존 .env로 새 볼륨을 초기화할 때는 결제 DB 준비를 나중에 할 수 있다.
SELECT :'payment_password' <> '' AS payment_configured \gset
\if :payment_configured
    SELECT :'payment_db' <> current_database() AND :'payment_user' <> current_user AS payment_is_separate \gset
    \if :payment_is_separate
        SELECT pg_advisory_lock(hashtext('ticketing.ensure-payment-db')) AS payment_lock \gset

        SELECT format('CREATE ROLE %I WITH LOGIN PASSWORD %L', :'payment_user', :'payment_password')
        WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'payment_user')
        \gexec

        SELECT NOT EXISTS (SELECT FROM pg_database WHERE datname = :'payment_db') AS create_payment_db \gset
        \if :create_payment_db
            SELECT format('CREATE DATABASE %I OWNER %I', :'payment_db', :'payment_user')
            \gexec
        \endif

        -- 로컬의 core 계정(POSTGRES_USER)은 슈퍼유저이므로 CONNECT 제한을 우회한다.
        BEGIN;
        REVOKE CONNECT ON DATABASE :"payment_db" FROM PUBLIC;
        GRANT CONNECT ON DATABASE :"payment_db" TO :"payment_user";
        ALTER ROLE :"payment_user" IN DATABASE :"payment_db" SET search_path = public;
        COMMIT;
    \else
        DO $$ BEGIN RAISE EXCEPTION 'payment DB와 계정은 core와 달라야 합니다'; END $$;
    \endif
\endif
