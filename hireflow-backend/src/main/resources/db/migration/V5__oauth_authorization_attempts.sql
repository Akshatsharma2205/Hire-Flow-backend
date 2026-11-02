-- ============================================================
-- HireFlow AI - V5 OAuth Authorization Attempts & Mailbox State
-- ============================================================
-- Adds server-managed OAuth 2.0 authorization attempt ledger for
-- single-use state verification, PKCE verifiers, tenant/user binding,
-- and TTL expiration.

-- ------------------------------------------------------------
-- 30. OAuth Authorization Attempts
-- ------------------------------------------------------------
CREATE TABLE oauth_authorization_attempts (
    id               UUID          NOT NULL,
    organization_id  UUID          NOT NULL,
    user_id          UUID          NOT NULL,
    provider         VARCHAR(30)   NOT NULL,
    state_token      VARCHAR(128)  NOT NULL,
    state            VARCHAR(128),
    code_verifier    VARCHAR(128),
    redirect_uri     VARCHAR(1000) NOT NULL,
    status           VARCHAR(24)   NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at       TIMESTAMP     NOT NULL,
    consumed_at      TIMESTAMP,
    CONSTRAINT pk_oauth_authorization_attempts PRIMARY KEY (id),
    CONSTRAINT fk_oauth_att_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_oauth_att_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uq_oauth_att_state UNIQUE (state_token),
    CONSTRAINT ck_oauth_att_status CHECK (status IN ('PENDING', 'CONSUMED', 'EXPIRED', 'FAILED'))
);

-- Performance indices for lookup, expiration sweep, and audit trail
CREATE INDEX idx_oauth_att_state ON oauth_authorization_attempts (state_token);
CREATE INDEX idx_oauth_att_state_col ON oauth_authorization_attempts (state);
CREATE INDEX idx_oauth_att_org_created ON oauth_authorization_attempts (organization_id, created_at);
CREATE INDEX idx_oauth_att_expires ON oauth_authorization_attempts (expires_at, status);

-- Supporting lookup index on existing mail_connections table
CREATE INDEX IF NOT EXISTS idx_mailconn_org_prov_box ON mail_connections (organization_id, provider, mailbox_address);
