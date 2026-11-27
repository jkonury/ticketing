CREATE TABLE settlement.daily_settlement (
    settlement_date date NOT NULL,
    performance_id bigint NOT NULL,
    organizer_id bigint NOT NULL,
    sale_count integer NOT NULL,
    sale_amount numeric NOT NULL,
    refund_count integer NOT NULL,
    refund_amount numeric NOT NULL,
    net_amount numeric NOT NULL,
    fee_rate numeric(5,4) NOT NULL,
    fee_amount numeric NOT NULL,
    payout_amount numeric NOT NULL,
    calculated_at timestamptz NOT NULL,
    platform_discount_amount numeric NOT NULL DEFAULT 0,
    PRIMARY KEY (settlement_date, performance_id)
);
CREATE INDEX idx_daily_settlement_organizer_date ON settlement.daily_settlement (organizer_id, settlement_date);
