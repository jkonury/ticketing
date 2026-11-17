-- DB 조건부 UPDATE 방식의 선착순 발급이 쓰는 남은 수량. Redis 전략에서는 갱신하지 않는다.
ALTER TABLE coupon.coupon_definition ADD COLUMN remaining_quantity integer;

UPDATE coupon.coupon_definition definition
SET remaining_quantity = GREATEST(
    definition.total_quantity - (SELECT count(*) FROM coupon.issued_coupon issued WHERE issued.definition_id = definition.id),
    0
)
WHERE definition.issue_mode = 'LIMITED';

ALTER TABLE coupon.coupon_definition
    ADD CONSTRAINT ck_coupon_definition_remaining_quantity CHECK (remaining_quantity IS NULL OR remaining_quantity >= 0);
