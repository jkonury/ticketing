CREATE TABLE booking.performance_sale (
    performance_id bigint  PRIMARY KEY,
    sale_enabled   boolean NOT NULL DEFAULT true,
    status_version bigint  NOT NULL DEFAULT 0
);

ALTER TABLE booking.schedule ADD COLUMN sale_enabled boolean NOT NULL DEFAULT true;
CREATE INDEX idx_booking_schedule_performance ON booking.schedule (performance_id);
