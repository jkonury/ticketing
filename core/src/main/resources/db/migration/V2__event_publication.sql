CREATE TABLE event_publication (
    id uuid PRIMARY KEY,
    publication_date timestamptz NOT NULL,
    listener_id varchar(512) NOT NULL,
    serialized_event text NOT NULL,
    event_type varchar(512) NOT NULL,
    completion_date timestamptz,
    last_resubmission_date timestamptz,
    completion_attempts integer,
    status varchar(20)
);
CREATE INDEX event_publication_completion_date_idx ON event_publication (completion_date);
