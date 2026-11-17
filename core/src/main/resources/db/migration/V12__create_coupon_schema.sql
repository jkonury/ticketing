CREATE SCHEMA coupon;

CREATE TABLE coupon.coupon_definition (
    id                bigserial PRIMARY KEY,
    name              varchar(100)  NOT NULL,
    description       varchar(500),
    discount_type     varchar(16)   NOT NULL CHECK (discount_type IN ('FIXED', 'RATE')),
    discount_value    numeric(12,0) NOT NULL CHECK (discount_value > 0),
    max_discount      numeric(12,0),
    min_order_amount  numeric(12,0) NOT NULL DEFAULT 0,
    performance_id    bigint,
    valid_from        timestamptz   NOT NULL,
    valid_until       timestamptz   NOT NULL,
    issue_mode        varchar(16)   NOT NULL CHECK (issue_mode IN ('UNLIMITED', 'LIMITED', 'DIRECT')),
    total_quantity    integer,
    status            varchar(16)   NOT NULL CHECK (status IN ('ACTIVE', 'STOPPED')),
    issue_started_at  timestamptz,
    created_at        timestamptz   NOT NULL,
    version           bigint        NOT NULL DEFAULT 0
);

CREATE TABLE coupon.issued_coupon (
    id               bigserial PRIMARY KEY,
    definition_id    bigint        NOT NULL REFERENCES coupon.coupon_definition (id),
    member_id        bigint        NOT NULL,
    status           varchar(16)   NOT NULL CHECK (status IN ('ISSUED', 'RESERVED', 'USED', 'REVOKED', 'EXPIRED')),
    order_id         bigint,
    discount_amount  numeric(12,0),
    reserved_until   timestamptz,
    issued_at        timestamptz   NOT NULL,
    used_at          timestamptz,
    version          bigint        NOT NULL DEFAULT 0,
    CONSTRAINT uq_issued_coupon_definition_member UNIQUE (definition_id, member_id)
);
CREATE INDEX idx_issued_coupon_member_id ON coupon.issued_coupon (member_id);
CREATE INDEX idx_issued_coupon_status_reserved_until ON coupon.issued_coupon (status, reserved_until);

CREATE TABLE coupon.use_mismatch (
    id                bigserial PRIMARY KEY,
    issued_coupon_id  bigint       NOT NULL,
    order_id          bigint       NOT NULL,
    previous_status   varchar(16)  NOT NULL,
    detected_at       timestamptz  NOT NULL,
    detail            varchar(500)
);
