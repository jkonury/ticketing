CREATE TABLE catalog.venue (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       varchar(100) NOT NULL,
    created_at timestamptz  NOT NULL
);

CREATE TABLE catalog.venue_seat (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id    bigint      NOT NULL REFERENCES catalog.venue (id),
    section     varchar(20) NOT NULL,
    row_label   varchar(10) NOT NULL,
    seat_number int         NOT NULL,
    grade       varchar(10) NOT NULL,
    CONSTRAINT uk_venue_seat UNIQUE (venue_id, section, row_label, seat_number)
);

-- organizer_id는 회원 ID 값만 저장한다 (member 스키마로 FK를 만들지 않는다)
CREATE TABLE catalog.performance (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organizer_id bigint       NOT NULL,
    title        varchar(200) NOT NULL,
    description  text         NOT NULL,
    venue_id     bigint       NOT NULL REFERENCES catalog.venue (id),
    created_at   timestamptz  NOT NULL
);

CREATE INDEX idx_performance_organizer ON catalog.performance (organizer_id);

CREATE TABLE catalog.performance_price (
    performance_id bigint        NOT NULL REFERENCES catalog.performance (id),
    grade          varchar(10)   NOT NULL,
    price          numeric(12,0) NOT NULL,
    PRIMARY KEY (performance_id, grade)
);

CREATE TABLE catalog.schedule (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    performance_id bigint      NOT NULL REFERENCES catalog.performance (id),
    starts_at      timestamptz NOT NULL,
    created_at     timestamptz NOT NULL,
    CONSTRAINT uk_schedule_performance_starts UNIQUE (performance_id, starts_at)
);
