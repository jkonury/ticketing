-- catalog 스키마로 FK를 만들지 않는다. schedule/schedule_seat는 ScheduleRegistered 이벤트로 받은 사본이다.
CREATE TABLE booking.schedule (
    schedule_id       bigint       PRIMARY KEY,
    performance_id    bigint       NOT NULL,
    performance_title varchar(200) NOT NULL,
    organizer_id      bigint       NOT NULL,
    starts_at         timestamptz  NOT NULL,
    received_at       timestamptz  NOT NULL
);

CREATE TABLE booking.schedule_seat (
    schedule_id bigint        NOT NULL,
    seat_id     bigint        NOT NULL,
    grade       varchar(10)   NOT NULL,
    label       varchar(100)  NOT NULL,
    price       numeric(12,0) NOT NULL,
    PRIMARY KEY (schedule_id, seat_id)
);

CREATE TABLE booking.booking (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id            bigint        NOT NULL,
    schedule_id          bigint        NOT NULL,
    status               varchar(20)   NOT NULL,
    total_amount         numeric(12,0) NOT NULL,
    hold_token           varchar(36)   NOT NULL,
    idempotency_key      varchar(100)  NOT NULL,
    expires_at           timestamptz   NOT NULL,
    payment_requested_at timestamptz,
    confirmed_at         timestamptz,
    cancelled_at         timestamptz,
    cancel_reason        varchar(30),
    created_at           timestamptz   NOT NULL,
    -- JPA @Version. 결제 결과 리스너와 사용자 취소가 같은 예매를 동시에 바꿀 수 있다
    version              bigint        NOT NULL,
    CONSTRAINT uk_booking_member_idempotency UNIQUE (member_id, idempotency_key)
);

CREATE INDEX idx_booking_member_id_desc ON booking.booking (member_id, id DESC);

CREATE TABLE booking.booking_seat (
    booking_id bigint        NOT NULL REFERENCES booking.booking (id),
    seat_id    bigint        NOT NULL,
    price      numeric(12,0) NOT NULL,
    PRIMARY KEY (booking_id, seat_id)
);

-- 좌석 중복 판매의 최종 방어선. Redis 선점과 무관하게 (회차, 좌석)은 한 번만 팔린다
CREATE TABLE booking.sold_seat (
    schedule_id bigint      NOT NULL,
    seat_id     bigint      NOT NULL,
    booking_id  bigint      NOT NULL,
    sold_at     timestamptz NOT NULL,
    PRIMARY KEY (schedule_id, seat_id)
);
