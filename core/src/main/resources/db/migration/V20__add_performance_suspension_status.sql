ALTER TABLE catalog.performance
    ADD COLUMN status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN suspension_version bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT performance_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED'));
