-- ============================================================
-- HireFlow AI - V6 Email Rule Engine, Mailbox Checkpoints, &
-- Ingestion Lifecycle Tracking
-- ============================================================

-- ------------------------------------------------------------
-- 31. Email Rules (Organization-scoped configurable rules)
-- ------------------------------------------------------------
CREATE TABLE email_rules (
    id                          UUID          NOT NULL,
    organization_id             UUID          NOT NULL,
    name                        VARCHAR(200)  NOT NULL,
    description                 VARCHAR(1000),
    priority                    INT           NOT NULL DEFAULT 100,
    enabled                     BOOLEAN       NOT NULL DEFAULT TRUE,
    connection_id               UUID,
    folder_filter               VARCHAR(120),
    sender_include_json         TEXT,
    sender_exclude_json         TEXT,
    subject_keywords_json       TEXT,
    subject_exclude_json        TEXT,
    body_keywords_json          TEXT,
    body_exclude_json           TEXT,
    require_attachment          BOOLEAN       NOT NULL DEFAULT TRUE,
    allowed_mimes_json          TEXT,
    min_attachment_size_bytes   BIGINT,
    max_attachment_size_bytes   BIGINT,
    target_job_id               UUID,
    duplicate_policy            VARCHAR(50)   NOT NULL DEFAULT 'UPDATE_PROFILE_ONLY',
    action_on_match             VARCHAR(30)   NOT NULL DEFAULT 'PROCESS',
    created_by                  UUID,
    created_at                  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_email_rules PRIMARY KEY (id),
    CONSTRAINT fk_email_rules_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_email_rules_conn FOREIGN KEY (connection_id) REFERENCES mail_connections(id) ON DELETE CASCADE,
    CONSTRAINT fk_email_rules_job FOREIGN KEY (target_job_id) REFERENCES jobs(id) ON DELETE SET NULL,
    CONSTRAINT ck_email_rules_action CHECK (action_on_match IN ('PROCESS', 'ROUTE_TO_REVIEW', 'EXCLUDE')),
    CONSTRAINT ck_email_rules_dup_policy CHECK (duplicate_policy IN ('ALLOW_NEW_SUBMISSION', 'UPDATE_PROFILE_ONLY', 'REJECT_DUPLICATE', 'FLAG_HR_REVIEW'))
);

CREATE INDEX idx_email_rules_org_priority ON email_rules (organization_id, priority ASC, enabled);
CREATE INDEX idx_email_rules_conn ON email_rules (connection_id);
CREATE INDEX idx_email_rules_job ON email_rules (target_job_id);

-- ------------------------------------------------------------
-- 32. Inbound Message Processing Lifecycle Additions
-- ------------------------------------------------------------
ALTER TABLE inbound_messages ADD COLUMN matched_rule_id UUID;
ALTER TABLE inbound_messages ADD COLUMN rule_action VARCHAR(50);
ALTER TABLE inbound_messages ADD COLUMN rule_explanation TEXT;
ALTER TABLE inbound_messages ADD CONSTRAINT fk_inbmsg_rule FOREIGN KEY (matched_rule_id) REFERENCES email_rules(id) ON DELETE SET NULL;

CREATE INDEX idx_inbmsg_org_status ON inbound_messages (organization_id, processing_status);
CREATE INDEX idx_inbmsg_matched_rule ON inbound_messages (matched_rule_id);

-- ------------------------------------------------------------
-- 33. Mail Connections Sync Checkpoint & Health Tracking
-- ------------------------------------------------------------
ALTER TABLE mail_connections ADD COLUMN initial_sync_completed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE mail_connections ADD COLUMN consecutive_failures INT NOT NULL DEFAULT 0;
ALTER TABLE mail_connections ADD COLUMN sync_status_details TEXT;
ALTER TABLE mail_connections ADD COLUMN last_attempt_at TIMESTAMP;

-- ------------------------------------------------------------
-- 34. Identity Review Cases Scope & Ambiguity Extensions
-- ------------------------------------------------------------
ALTER TABLE identity_review_cases ADD COLUMN message_id UUID;
ALTER TABLE identity_review_cases ADD COLUMN attachment_id UUID;
ALTER TABLE identity_review_cases ADD COLUMN suggested_job_id UUID;
ALTER TABLE identity_review_cases ADD CONSTRAINT fk_irc_msg FOREIGN KEY (message_id) REFERENCES inbound_messages(id) ON DELETE SET NULL;
ALTER TABLE identity_review_cases ADD CONSTRAINT fk_irc_att FOREIGN KEY (attachment_id) REFERENCES inbound_attachments(id) ON DELETE SET NULL;
ALTER TABLE identity_review_cases ADD CONSTRAINT fk_irc_sugg_job FOREIGN KEY (suggested_job_id) REFERENCES jobs(id) ON DELETE SET NULL;

CREATE INDEX idx_irc_org_resolution ON identity_review_cases (organization_id, resolution);
CREATE INDEX idx_irc_reason ON identity_review_cases (organization_id, reason_code);
