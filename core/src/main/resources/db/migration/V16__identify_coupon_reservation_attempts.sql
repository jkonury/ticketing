ALTER TABLE booking.booking
    ADD COLUMN coupon_attempt_id bigint NOT NULL DEFAULT 0;

ALTER TABLE coupon.issued_coupon
    ADD COLUMN reservation_attempt_id bigint NOT NULL DEFAULT 0;

CREATE TABLE coupon.reservation_attempt (
    issued_coupon_id bigint NOT NULL REFERENCES coupon.issued_coupon (id),
    order_id bigint NOT NULL,
    attempt_id bigint NOT NULL,
    reject_reason varchar(64),
    released_version bigint,
    PRIMARY KEY (issued_coupon_id, order_id)
);

INSERT INTO coupon.reservation_attempt (issued_coupon_id, order_id, attempt_id)
SELECT id, order_id, reservation_attempt_id
FROM coupon.issued_coupon
WHERE status IN ('RESERVED', 'USED') AND order_id IS NOT NULL;
