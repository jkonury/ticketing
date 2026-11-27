CREATE SCHEMA settlement;

CREATE TABLE settlement.booking_sale (
    booking_id bigint PRIMARY KEY,
    performance_id bigint NOT NULL,
    organizer_id bigint NOT NULL,
    schedule_id bigint NOT NULL,
    amount numeric(12,0) NOT NULL,
    confirmed_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    list_amount numeric(12,0) NOT NULL,
    discount_amount numeric(12,0) NOT NULL
);

CREATE INDEX idx_booking_sale_confirmed_at ON settlement.booking_sale (confirmed_at);

CREATE TABLE settlement.booking_refund (
    booking_id bigint PRIMARY KEY,
    performance_id bigint NOT NULL,
    organizer_id bigint NOT NULL,
    reason varchar NOT NULL,
    cancelled_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL
);

CREATE INDEX idx_booking_refund_cancelled_at ON settlement.booking_refund (cancelled_at);
