ALTER TABLE catalog.performance
    DROP CONSTRAINT performance_status_check,
    ADD CONSTRAINT performance_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CANCELLED'));
