ALTER TABLE booking.performance_sale ADD COLUMN cancelled boolean NOT NULL DEFAULT false;
ALTER TABLE booking.schedule ADD COLUMN cancelled boolean NOT NULL DEFAULT false;
