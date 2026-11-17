CREATE SCHEMA audit;

CREATE TABLE audit.admin_action (
    id bigserial PRIMARY KEY,
    actor_id bigint,
    action varchar(64) NOT NULL,
    target_type varchar(64) NOT NULL,
    target_id varchar(100) NOT NULL,
    detail jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    source_event_id uuid NOT NULL UNIQUE
);
CREATE INDEX idx_admin_action_occurred_at ON audit.admin_action (occurred_at);
CREATE INDEX idx_admin_action_actor_id ON audit.admin_action (actor_id, id DESC);
CREATE INDEX idx_admin_action_action ON audit.admin_action (action, id DESC);
