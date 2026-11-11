CREATE TABLE payment.pg_transaction_snapshot (
    target_date date NOT NULL,
    transaction_id varchar(100) NOT NULL,
    order_id bigint NOT NULL,
    amount numeric NOT NULL,
    status varchar(32) NOT NULL,
    requested_at timestamptz NOT NULL,
    approved_at timestamptz,
    refunded_at timestamptz,
    fetched_at timestamptz NOT NULL,
    PRIMARY KEY (target_date, transaction_id)
);
CREATE TABLE payment.reconciliation_mismatch (
    target_date date NOT NULL,
    order_id bigint NOT NULL,
    mismatch_type varchar(32) NOT NULL,
    pg_transaction_id varchar(100),
    pg_status varchar(32),
    pg_amount numeric,
    internal_payment_id bigint,
    internal_status varchar(32),
    internal_amount numeric,
    detected_at timestamptz NOT NULL,
    PRIMARY KEY (target_date, order_id, mismatch_type)
);
