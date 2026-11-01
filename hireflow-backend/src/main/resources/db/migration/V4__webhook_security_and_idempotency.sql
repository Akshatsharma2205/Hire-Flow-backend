-- ============================================================
-- HireFlow AI - V4 Webhook security and durable idempotency
-- ============================================================
-- Adds per-organization webhook credentials (secrets encrypted at
-- rest with the application AES-256-GCM key) and durable delivery
-- records used for replay protection (nonce) and idempotency
-- (idempotency key + body digest).
--
-- Retention note: webhook_deliveries rows are the durable replay
-- guard. They must be retained for AT LEAST the configured replay
-- validity window (default 5 minutes); a realistic retention period
-- is 7-30 days. No cleanup job is introduced by this migration -
-- removing rows earlier than the replay window would re-open replay.

-- -----------------------------------------------
-- 28. Webhook Credentials (one or more per organization)
-- -----------------------------------------------
CREATE TABLE webhook_credentials (
    id               UUID         NOT NULL,
    organization_id  UUID         NOT NULL,
    key_id           VARCHAR(64)  NOT NULL,
    encrypted_secret VARCHAR(512) NOT NULL,
    status           VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       UUID,
    revoked_at       TIMESTAMP,
    revoked_by       UUID,
    CONSTRAINT pk_webhook_credentials PRIMARY KEY (id),
    CONSTRAINT fk_whcred_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT uq_webhook_credentials_key_id UNIQUE (key_id),
    CONSTRAINT ck_webhook_credentials_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE INDEX idx_webhook_credentials_org ON webhook_credentials (organization_id, status);

-- -----------------------------------------------
-- 29. Webhook Deliveries (durable replay + idempotency ledger)
-- -----------------------------------------------
CREATE TABLE webhook_deliveries (
    id                 UUID         NOT NULL,
    organization_id    UUID         NOT NULL,
    credential_id      UUID         NOT NULL,
    idempotency_key    VARCHAR(200) NOT NULL,
    nonce              VARCHAR(128) NOT NULL,
    body_sha256        VARCHAR(64)  NOT NULL,
    status             VARCHAR(24)  NOT NULL,
    inbound_message_id UUID,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_webhook_deliveries PRIMARY KEY (id),
    CONSTRAINT fk_whdel_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_whdel_cred FOREIGN KEY (credential_id) REFERENCES webhook_credentials(id),
    CONSTRAINT fk_whdel_msg FOREIGN KEY (inbound_message_id) REFERENCES inbound_messages(id),
    -- Idempotency identity: one logical delivery per organization + key.
    CONSTRAINT uq_webhook_deliveries_org_idem UNIQUE (organization_id, idempotency_key),
    -- Replay identity: one delivery per organization + credential + nonce.
    CONSTRAINT uq_webhook_deliveries_org_cred_nonce UNIQUE (organization_id, credential_id, nonce)
);

CREATE INDEX idx_webhook_deliveries_org_created ON webhook_deliveries (organization_id, created_at);
