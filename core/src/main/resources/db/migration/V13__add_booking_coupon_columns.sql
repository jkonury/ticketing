-- 쿠폰 적용 상태와 할인액. coupon 스키마는 참조하지 않고 발급 쿠폰 ID만 값으로 보관한다
ALTER TABLE booking.booking
    ADD COLUMN coupon_issued_id      bigint,
    ADD COLUMN coupon_state          varchar(16),
    ADD COLUMN discount_amount       numeric(12,0) NOT NULL DEFAULT 0,
    ADD COLUMN coupon_reject_reason  varchar(64),
    ADD CONSTRAINT ck_booking_coupon_state CHECK (coupon_state IN ('RESERVING', 'RESERVED', 'REJECTED'));
