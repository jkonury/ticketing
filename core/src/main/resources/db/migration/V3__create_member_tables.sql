CREATE TABLE member.member (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         varchar(255) NOT NULL,
    password_hash varchar(100) NOT NULL,
    name          varchar(50)  NOT NULL,
    role          varchar(20)  NOT NULL,
    created_at    timestamptz  NOT NULL,
    CONSTRAINT uk_member_email UNIQUE (email)
);

-- 토큰 원문은 저장하지 않고 SHA-256 해시만 저장한다
CREATE TABLE member.refresh_token (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id  bigint      NOT NULL REFERENCES member.member (id),
    token_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_token_member ON member.refresh_token (member_id);
