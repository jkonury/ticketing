-- Spring Modulith 이벤트 발행 레지스트리 (JPA 저장소). 특정 모듈의 데이터가 아니므로 public 스키마에 둔다.
-- 출처: spring-modulith-events-jdbc 2.1.1의 schemas/v2/schema-postgresql.sql (JPA 엔티티와 컬럼이 같다)
CREATE TABLE event_publication
(
    id                     UUID                     NOT NULL,
    listener_id            TEXT                     NOT NULL,
    event_type             TEXT                     NOT NULL,
    serialized_event       TEXT                     NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);
