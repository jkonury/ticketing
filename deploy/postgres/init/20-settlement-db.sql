\getenv core_db TICKETING_POSTGRES_DB
\getenv payment_db TICKETING_PAYMENT_POSTGRES_DB
\getenv payment_user TICKETING_PAYMENT_POSTGRES_USER
\getenv settlement_db TICKETING_SETTLEMENT_POSTGRES_DB
\getenv settlement_user TICKETING_SETTLEMENT_POSTGRES_USER
\set settlement_password ''
\getenv settlement_password TICKETING_SETTLEMENT_POSTGRES_PASSWORD

-- 오류가 발생해도 계정 비밀번호가 서버 로그에 남지 않게 한다.
SET log_min_error_statement = panic;
SET log_statement = none;
SET log_min_duration_statement = -1;

SELECT :'settlement_password' <> '' AS configured \gset
\if :configured
    SELECT :'settlement_db' NOT IN (:'core_db', :'payment_db', current_database(), 'postgres', 'template0', 'template1')
       AND :'settlement_user' NOT IN (:'payment_user', current_user)
       AND :'settlement_db' <> '' AND octet_length(:'settlement_db') <= 63
       AND :'settlement_user' <> '' AND octet_length(:'settlement_user') <= 63 AS isolated \gset
    \if :isolated
        SELECT pg_advisory_lock(hashtext('ticketing.ensure-settlement-db')) AS setup_lock \gset

        SELECT NOT EXISTS (
            SELECT FROM pg_roles r WHERE r.rolname = :'settlement_user'
              AND (NOT r.rolcanlogin OR r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls
                   OR EXISTS (SELECT FROM pg_auth_members m WHERE m.member = r.oid OR m.roleid = r.oid))
        ) AND NOT EXISTS (
            SELECT FROM pg_database d JOIN pg_roles r ON r.oid = d.datdba
            WHERE (d.datname = :'settlement_db' AND r.rolname <> :'settlement_user')
               OR (d.datname <> :'settlement_db' AND r.rolname = :'settlement_user')
        ) AS dedicated \gset
        \if :dedicated
            SELECT format('CREATE ROLE %I WITH LOGIN PASSWORD %L', :'settlement_user', :'settlement_password')
            WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'settlement_user')
            \gexec

            SELECT format('CREATE DATABASE %I OWNER %I', :'settlement_db', :'settlement_user')
            WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'settlement_db')
            \gexec

            BEGIN;
            REVOKE CONNECT ON DATABASE :"settlement_db" FROM PUBLIC;
            GRANT CONNECT ON DATABASE :"settlement_db" TO :"settlement_user";
            REVOKE CONNECT ON DATABASE :"core_db" FROM PUBLIC, :"settlement_user";
            COMMIT;
        \else
            DO $$ BEGIN RAISE EXCEPTION 'settlement DB 소유자와 계정 권한을 확인하세요'; END $$;
        \endif
    \else
        DO $$ BEGIN RAISE EXCEPTION 'settlement DB와 계정은 core 및 payment와 달라야 합니다'; END $$;
    \endif
\endif
