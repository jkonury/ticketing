ALTER TABLE settlement.booking_sale ADD COLUMN list_amount numeric(12,0);
ALTER TABLE settlement.booking_sale ADD COLUMN discount_amount numeric(12,0);
UPDATE settlement.booking_sale SET list_amount = amount, discount_amount = 0;
ALTER TABLE settlement.booking_sale ALTER COLUMN list_amount SET NOT NULL;
ALTER TABLE settlement.booking_sale ALTER COLUMN discount_amount SET NOT NULL;

ALTER TABLE settlement.daily_settlement ADD COLUMN platform_discount_amount numeric NOT NULL DEFAULT 0;
