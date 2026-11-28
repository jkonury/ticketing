ALTER TABLE member.member
    ADD COLUMN status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    ADD CONSTRAINT member_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED'));
