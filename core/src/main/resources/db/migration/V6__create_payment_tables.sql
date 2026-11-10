-- booking 스키마로 FK를 만들지 않는다. order_id는 요청자가 정한 주문 식별자이며 payment는 그 의미를 모른다.
CREATE TABLE payment.payment (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id            bigint        NOT NULL,
    member_id           bigint        NOT NULL,
    amount              numeric(12,0) NOT NULL,
    status              varchar(20)   NOT NULL,
    idempotency_key     varchar(100)  NOT NULL,
    pg_transaction_id   varchar(100),
    failure_reason      varchar(255),
    requested_at        timestamptz   NOT NULL,
    approved_at         timestamptz,
    failed_at           timestamptz,
    refund_requested_at timestamptz,
    refunded_at         timestamptz,
    version             bigint        NOT NULL,
    CONSTRAINT uk_payment_order_id UNIQUE (order_id),
    CONSTRAINT uk_payment_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT uk_payment_pg_transaction_id UNIQUE (pg_transaction_id)
);

-- 웹훅 중복 제거용. event_id가 이미 있으면 재전송이다
CREATE TABLE payment.webhook_event (
    event_id       varchar(64)  PRIMARY KEY,
    type           varchar(30)  NOT NULL,
    transaction_id varchar(100) NOT NULL,
    received_at    timestamptz  NOT NULL
);
