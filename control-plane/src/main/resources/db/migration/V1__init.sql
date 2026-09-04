-- NexusOps control-plane schema. Flyway owns this; Hibernate never mutates it.

CREATE TABLE incident (
    id             VARCHAR(64)  PRIMARY KEY,
    correlation_id VARCHAR(128) NOT NULL,
    service_ref    VARCHAR(128) NOT NULL,
    environment    VARCHAR(16)  NOT NULL,
    signal         VARCHAR(512) NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_incident_service_created ON incident (service_ref, created_at);
CREATE INDEX idx_incident_status ON incident (status);

CREATE TABLE remediation_plan (
    id             VARCHAR(64)  PRIMARY KEY,
    incident_id    VARCHAR(64)  NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    hypothesis     TEXT,
    confidence     DOUBLE PRECISION NOT NULL,
    blast_radius   VARCHAR(16)  NOT NULL,
    actions_json   TEXT         NOT NULL,
    evidence_json  TEXT         NOT NULL,
    escalate       BOOLEAN      NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_plan_incident ON remediation_plan (incident_id);

CREATE TABLE approval_record (
    id               VARCHAR(64)  PRIMARY KEY,
    incident_id      VARCHAR(64)  NOT NULL,
    plan_id          VARCHAR(64)  NOT NULL,
    correlation_id   VARCHAR(128) NOT NULL,
    state            VARCHAR(32)  NOT NULL,
    policy_decision  VARCHAR(32)  NOT NULL,
    fired_rules      TEXT,
    risk_score       DOUBLE PRECISION NOT NULL,
    approver_subject VARCHAR(256),
    execution_key    VARCHAR(128) NOT NULL,
    detail           TEXT,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    expires_at       TIMESTAMPTZ,
    version          BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_approval_state ON approval_record (state);

CREATE TABLE execution_record (
    id            VARCHAR(64)  PRIMARY KEY,
    execution_key VARCHAR(128) NOT NULL UNIQUE,
    incident_id   VARCHAR(64)  NOT NULL,
    action_type   VARCHAR(32)  NOT NULL,
    target_ref    VARCHAR(128) NOT NULL,
    dry_run       BOOLEAN      NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    intended_op   TEXT,
    pre_state     TEXT,
    post_state    TEXT,
    detail        TEXT,
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE verification_result (
    id           VARCHAR(64) PRIMARY KEY,
    incident_id  VARCHAR(64) NOT NULL,
    execution_id VARCHAR(64),
    resolved     BOOLEAN     NOT NULL,
    detail       TEXT,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE audit_entry (
    seq            BIGSERIAL    PRIMARY KEY,
    incident_id    VARCHAR(64),
    correlation_id VARCHAR(128),
    event_type     VARCHAR(64)  NOT NULL,
    payload_json   TEXT         NOT NULL,
    actor          VARCHAR(256),
    prev_hash      VARCHAR(64)  NOT NULL,
    hash           VARCHAR(64)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_audit_incident ON audit_entry (incident_id);
