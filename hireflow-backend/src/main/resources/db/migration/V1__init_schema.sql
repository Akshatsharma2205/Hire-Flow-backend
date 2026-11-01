-- ============================================================
-- HireFlow AI - V1 Initial Schema (H2 Compatible)
-- ============================================================

-- -----------------------------------------------
-- 1. Organizations
-- -----------------------------------------------
CREATE TABLE organizations (
    id                UUID         NOT NULL,
    name              VARCHAR(200) NOT NULL,
    slug              VARCHAR(100) NOT NULL,
    status            VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    default_timezone  VARCHAR(64)  NOT NULL DEFAULT 'Asia/Kolkata',
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_organizations PRIMARY KEY (id),
    CONSTRAINT uq_organizations_slug UNIQUE (slug)
);

-- -----------------------------------------------
-- 2. Users
-- -----------------------------------------------
CREATE TABLE users (
    id                UUID         NOT NULL,
    email             VARCHAR(320) NOT NULL,
    password_hash     VARCHAR(255),
    display_name      VARCHAR(200),
    auth_subject      VARCHAR(255),
    mfa_enabled       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
);

-- -----------------------------------------------
-- 3. Organization Users (join table with roles)
-- -----------------------------------------------
CREATE TABLE organization_users (
    id                    UUID         NOT NULL,
    organization_id       UUID         NOT NULL,
    user_id               UUID         NOT NULL,
    role                  VARCHAR(32)  NOT NULL,
    status                VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    extension_token_hash  VARCHAR(128),
    joined_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_organization_users PRIMARY KEY (id),
    CONSTRAINT fk_orgusers_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_orgusers_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uq_orgusers_org_user UNIQUE (organization_id, user_id)
);

-- -----------------------------------------------
-- 4. Mail Connections
-- -----------------------------------------------
CREATE TABLE mail_connections (
    id                UUID         NOT NULL,
    organization_id   UUID         NOT NULL,
    provider          VARCHAR(30)  NOT NULL,
    mailbox_address   VARCHAR(320) NOT NULL,
    imap_host         VARCHAR(200),
    imap_port         INT,
    folder_filter     VARCHAR(120) DEFAULT 'INBOX',
    keyword_filter_json TEXT,
    oauth_secret_ref  TEXT,
    permission_scope  VARCHAR(4000),
    sync_cursor       VARCHAR(4000),
    last_success_at   TIMESTAMP,
    status            VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    created_by        UUID,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_mail_connections PRIMARY KEY (id),
    CONSTRAINT fk_mailconn_org FOREIGN KEY (organization_id) REFERENCES organizations(id)
);

-- -----------------------------------------------
-- 5. Inbound Messages
-- -----------------------------------------------
CREATE TABLE inbound_messages (
    id                    UUID         NOT NULL,
    organization_id       UUID         NOT NULL,
    connection_id         UUID,
    provider_message_id   VARCHAR(500),
    internet_message_id   VARCHAR(500),
    sender_email          VARCHAR(320),
    sender_name           VARCHAR(200),
    original_sender_claim VARCHAR(320),
    source_type           VARCHAR(30)  NOT NULL,
    subject               VARCHAR(4000),
    job_id                UUID,
    received_at           TIMESTAMP,
    processing_status     VARCHAR(30)  NOT NULL DEFAULT 'RECEIVED',
    failure_code          VARCHAR(80),
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_inbound_messages PRIMARY KEY (id),
    CONSTRAINT fk_inbmsg_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_inbmsg_conn FOREIGN KEY (connection_id) REFERENCES mail_connections(id),
    CONSTRAINT uq_inbmsg_org_conn_provid UNIQUE (organization_id, connection_id, provider_message_id)
);

-- -----------------------------------------------
-- 6. Resume Files
-- -----------------------------------------------
CREATE TABLE resume_files (
    id                UUID         NOT NULL,
    organization_id   UUID         NOT NULL,
    sha256            VARCHAR(64)  NOT NULL,
    s3_bucket         VARCHAR(200),
    s3_object_key     VARCHAR(4000),
    size_bytes        BIGINT,
    mime_type         VARCHAR(120),
    malware_status    VARCHAR(30)  NOT NULL DEFAULT 'CLEAN',
    extracted_raw_text TEXT,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_resume_files PRIMARY KEY (id),
    CONSTRAINT fk_resumefiles_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT uq_resumefiles_org_sha256 UNIQUE (organization_id, sha256)
);

-- -----------------------------------------------
-- 7. Inbound Attachments
-- -----------------------------------------------
CREATE TABLE inbound_attachments (
    id                UUID         NOT NULL,
    organization_id   UUID         NOT NULL,
    message_id        UUID         NOT NULL,
    attachment_index  INT          NOT NULL,
    original_filename VARCHAR(4000),
    declared_mime     VARCHAR(120),
    detected_mime     VARCHAR(120),
    size_bytes        BIGINT,
    sha256            VARCHAR(64),
    resume_file_id    UUID,
    status            VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_inbound_attachments PRIMARY KEY (id),
    CONSTRAINT fk_inbatt_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_inbatt_msg FOREIGN KEY (message_id) REFERENCES inbound_messages(id),
    CONSTRAINT fk_inbatt_resume FOREIGN KEY (resume_file_id) REFERENCES resume_files(id),
    CONSTRAINT uq_inbatt_msg_idx UNIQUE (message_id, attachment_index)
);

-- -----------------------------------------------
-- 8. Candidates
-- -----------------------------------------------
CREATE TABLE candidates (
    id                           UUID         NOT NULL,
    organization_id              UUID         NOT NULL,
    display_name                 VARCHAR(250),
    primary_email                VARCHAR(320),
    primary_phone                VARCHAR(50),
    linkedin_url                 VARCHAR(4000),
    current_resume_version_id    UUID,
    current_profile_version_id   UUID,
    current_experience_months    INT,
    experience_confidence        VARCHAR(24),
    current_city                 VARCHAR(150),
    last_application_at          TIMESTAMP,
    profile_updated_at           TIMESTAMP,
    status                       VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    version                      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_candidates PRIMARY KEY (id),
    CONSTRAINT fk_candidates_org FOREIGN KEY (organization_id) REFERENCES organizations(id)
);

CREATE INDEX idx_candidates_org_lastapp ON candidates (organization_id, last_application_at DESC, id);

-- -----------------------------------------------
-- 9. Candidate Identifiers
-- -----------------------------------------------
CREATE TABLE candidate_identifiers (
    id                   UUID         NOT NULL,
    organization_id      UUID         NOT NULL,
    candidate_id         UUID         NOT NULL,
    identifier_type      VARCHAR(25)  NOT NULL,
    normalized_value     VARCHAR(500) NOT NULL,
    source_type          VARCHAR(30)  NOT NULL,
    verification_status  VARCHAR(24)  NOT NULL DEFAULT 'UNVERIFIED',
    first_seen_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_candidate_identifiers PRIMARY KEY (id),
    CONSTRAINT fk_candid_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_candid_cand FOREIGN KEY (candidate_id) REFERENCES candidates(id)
);

CREATE INDEX idx_candid_org_type_val ON candidate_identifiers (organization_id, identifier_type, normalized_value);

-- -----------------------------------------------
-- 10. Resume Versions
-- -----------------------------------------------
CREATE TABLE resume_versions (
    id                   UUID         NOT NULL,
    organization_id      UUID         NOT NULL,
    candidate_id         UUID         NOT NULL,
    resume_file_id       UUID         NOT NULL,
    version_number       INT          NOT NULL,
    received_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    document_date        DATE,
    source_attachment_id UUID,
    parse_status         VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    validation_status    VARCHAR(30)  NOT NULL DEFAULT 'VALID',
    parser_version       VARCHAR(50),
    processed_at         TIMESTAMP,
    promoted_at          TIMESTAMP,
    superseded_at        TIMESTAMP,
    CONSTRAINT pk_resume_versions PRIMARY KEY (id),
    CONSTRAINT fk_resver_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_resver_cand FOREIGN KEY (candidate_id) REFERENCES candidates(id),
    CONSTRAINT fk_resver_file FOREIGN KEY (resume_file_id) REFERENCES resume_files(id),
    CONSTRAINT uq_resver_org_cand_ver UNIQUE (organization_id, candidate_id, version_number)
);

-- -----------------------------------------------
-- 11. Candidate Profile Versions
-- -----------------------------------------------
CREATE TABLE candidate_profile_versions (
    id                   UUID         NOT NULL,
    organization_id      UUID         NOT NULL,
    candidate_id         UUID         NOT NULL,
    resume_version_id    UUID         NOT NULL,
    profile_version      BIGINT       NOT NULL,
    full_name            VARCHAR(250),
    headline             VARCHAR(250),
    experience_months    INT,
    experience_basis     VARCHAR(50),
    location_text        VARCHAR(250),
    education_json       TEXT,
    source_evidence_json TEXT,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_candidate_profile_versions PRIMARY KEY (id),
    CONSTRAINT fk_cpv_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_cpv_cand FOREIGN KEY (candidate_id) REFERENCES candidates(id),
    CONSTRAINT fk_cpv_resver FOREIGN KEY (resume_version_id) REFERENCES resume_versions(id)
);

-- -----------------------------------------------
-- 12. Skills
-- -----------------------------------------------
CREATE TABLE skills (
    id              UUID         NOT NULL,
    canonical_name  VARCHAR(150) NOT NULL,
    category        VARCHAR(80),
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_skills PRIMARY KEY (id),
    CONSTRAINT uq_skills_name UNIQUE (canonical_name)
);

-- -----------------------------------------------
-- 13. Skill Aliases
-- -----------------------------------------------
CREATE TABLE skill_aliases (
    id               UUID         NOT NULL,
    skill_id         UUID         NOT NULL,
    alias_normalized VARCHAR(150) NOT NULL,
    CONSTRAINT pk_skill_aliases PRIMARY KEY (id),
    CONSTRAINT fk_skillalias_skill FOREIGN KEY (skill_id) REFERENCES skills(id),
    CONSTRAINT uq_skillalias_alias UNIQUE (alias_normalized)
);

-- -----------------------------------------------
-- 14. Candidate Skills
-- -----------------------------------------------
CREATE TABLE candidate_skills (
    id                 UUID          NOT NULL,
    organization_id    UUID          NOT NULL,
    candidate_id       UUID          NOT NULL,
    skill_id           UUID          NOT NULL,
    profile_version_id UUID          NOT NULL,
    evidence_type      VARCHAR(30),
    confidence         NUMERIC(4,3),
    last_confirmed_at  TIMESTAMP,
    CONSTRAINT pk_candidate_skills PRIMARY KEY (id),
    CONSTRAINT fk_candskill_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_candskill_cand FOREIGN KEY (candidate_id) REFERENCES candidates(id),
    CONSTRAINT fk_candskill_skill FOREIGN KEY (skill_id) REFERENCES skills(id),
    CONSTRAINT fk_candskill_profver FOREIGN KEY (profile_version_id) REFERENCES candidate_profile_versions(id)
);

CREATE INDEX idx_candskill_org_skill_cand ON candidate_skills (organization_id, skill_id, candidate_id);

-- -----------------------------------------------
-- 15. Jobs
-- -----------------------------------------------
CREATE TABLE jobs (
    id                 UUID         NOT NULL,
    organization_id    UUID         NOT NULL,
    title              VARCHAR(250) NOT NULL,
    department         VARCHAR(150),
    location           VARCHAR(200),
    status             VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    current_version_id UUID,
    created_by         UUID,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_jobs PRIMARY KEY (id),
    CONSTRAINT fk_jobs_org FOREIGN KEY (organization_id) REFERENCES organizations(id)
);

-- -----------------------------------------------
-- 16. Job Versions
-- -----------------------------------------------
CREATE TABLE job_versions (
    id                    UUID         NOT NULL,
    organization_id       UUID         NOT NULL,
    job_id                UUID         NOT NULL,
    version_number        INT          NOT NULL,
    jd_text               TEXT,
    jd_hash               VARCHAR(64),
    min_experience_months INT,
    max_experience_months INT,
    requirements_json     TEXT,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_job_versions PRIMARY KEY (id),
    CONSTRAINT fk_jobver_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_jobver_job FOREIGN KEY (job_id) REFERENCES jobs(id)
);

-- -----------------------------------------------
-- 17. Job Skill Requirements
-- -----------------------------------------------
CREATE TABLE job_skill_requirements (
    id              UUID          NOT NULL,
    job_version_id  UUID          NOT NULL,
    skill_id        UUID          NOT NULL,
    priority        VARCHAR(24)   NOT NULL,
    weight          NUMERIC(6,3),
    CONSTRAINT pk_job_skill_requirements PRIMARY KEY (id),
    CONSTRAINT fk_jsr_jobver FOREIGN KEY (job_version_id) REFERENCES job_versions(id),
    CONSTRAINT fk_jsr_skill FOREIGN KEY (skill_id) REFERENCES skills(id)
);

-- -----------------------------------------------
-- 18. Job Matches
-- -----------------------------------------------
CREATE TABLE job_matches (
    id                 UUID          NOT NULL,
    organization_id    UUID          NOT NULL,
    job_version_id     UUID          NOT NULL,
    candidate_id       UUID          NOT NULL,
    profile_version_id UUID          NOT NULL,
    scorer_version     VARCHAR(50),
    score              NUMERIC(6,3),
    explanation_json   TEXT,
    status             VARCHAR(24)  NOT NULL DEFAULT 'READY',
    computed_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_job_matches PRIMARY KEY (id),
    CONSTRAINT fk_jobmatch_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_jobmatch_jobver FOREIGN KEY (job_version_id) REFERENCES job_versions(id),
    CONSTRAINT fk_jobmatch_cand FOREIGN KEY (candidate_id) REFERENCES candidates(id),
    CONSTRAINT fk_jobmatch_profver FOREIGN KEY (profile_version_id) REFERENCES candidate_profile_versions(id),
    CONSTRAINT uq_jobmatch_ver_cand_prof_scorer UNIQUE (job_version_id, candidate_id, profile_version_id, scorer_version)
);

-- -----------------------------------------------
-- 19. Applications
-- -----------------------------------------------
CREATE TABLE applications (
    id                UUID         NOT NULL,
    organization_id   UUID         NOT NULL,
    candidate_id      UUID         NOT NULL,
    job_id            UUID         NOT NULL,
    attempt_number    INT          NOT NULL DEFAULT 1,
    current_stage     VARCHAR(50)  NOT NULL DEFAULT 'NEW',
    status            VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_applications PRIMARY KEY (id),
    CONSTRAINT fk_app_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_app_cand FOREIGN KEY (candidate_id) REFERENCES candidates(id),
    CONSTRAINT fk_app_job FOREIGN KEY (job_id) REFERENCES jobs(id)
);

-- -----------------------------------------------
-- 20. Application Submissions
-- -----------------------------------------------
CREATE TABLE application_submissions (
    id                       UUID         NOT NULL,
    organization_id          UUID         NOT NULL,
    application_id           UUID         NOT NULL,
    resume_version_id        UUID,
    submission_fingerprint   VARCHAR(255) NOT NULL,
    source_type              VARCHAR(30),
    submitted_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_application_submissions PRIMARY KEY (id),
    CONSTRAINT fk_appsub_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_appsub_app FOREIGN KEY (application_id) REFERENCES applications(id),
    CONSTRAINT fk_appsub_resver FOREIGN KEY (resume_version_id) REFERENCES resume_versions(id),
    CONSTRAINT uq_appsub_org_fingerprint UNIQUE (organization_id, submission_fingerprint)
);

-- -----------------------------------------------
-- 21. Application Stage History
-- -----------------------------------------------
CREATE TABLE application_stage_history (
    id             UUID         NOT NULL,
    application_id UUID         NOT NULL,
    from_stage     VARCHAR(50),
    to_stage       VARCHAR(50)  NOT NULL,
    changed_by     UUID,
    changed_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    note           VARCHAR(4000),
    CONSTRAINT pk_application_stage_history PRIMARY KEY (id),
    CONSTRAINT fk_appstage_app FOREIGN KEY (application_id) REFERENCES applications(id)
);

-- -----------------------------------------------
-- 22. Candidate Notes
-- -----------------------------------------------
CREATE TABLE candidate_notes (
    id              UUID         NOT NULL,
    organization_id UUID         NOT NULL,
    candidate_id    UUID         NOT NULL,
    author_id       UUID,
    content         TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_candidate_notes PRIMARY KEY (id),
    CONSTRAINT fk_candnote_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_candnote_cand FOREIGN KEY (candidate_id) REFERENCES candidates(id)
);

-- -----------------------------------------------
-- 23. Identity Review Cases
-- -----------------------------------------------
CREATE TABLE identity_review_cases (
    id                      UUID         NOT NULL,
    organization_id         UUID         NOT NULL,
    candidate_id            UUID,
    conflicting_candidate_id UUID,
    reason_code             VARCHAR(50)  NOT NULL,
    details                 TEXT,
    resolution              VARCHAR(50),
    resolved_by             UUID,
    resolved_at             TIMESTAMP,
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_identity_review_cases PRIMARY KEY (id),
    CONSTRAINT fk_irc_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_irc_cand FOREIGN KEY (candidate_id) REFERENCES candidates(id)
);

-- -----------------------------------------------
-- 24. Processing Jobs
-- -----------------------------------------------
CREATE TABLE processing_jobs (
    id              UUID         NOT NULL,
    organization_id UUID         NOT NULL,
    job_type        VARCHAR(50)  NOT NULL,
    reference_id    UUID,
    status          VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    attempt_count   INT          NOT NULL DEFAULT 0,
    max_attempts    INT          NOT NULL DEFAULT 3,
    next_attempt_at TIMESTAMP,
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP,
    failure_reason  VARCHAR(4000),
    context_json    TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_processing_jobs PRIMARY KEY (id),
    CONSTRAINT fk_procjob_org FOREIGN KEY (organization_id) REFERENCES organizations(id)
);

CREATE INDEX idx_procjob_status_next ON processing_jobs (status, next_attempt_at);
CREATE INDEX idx_procjob_org_status ON processing_jobs (organization_id, status);

CREATE INDEX idx_resver_org_cand ON resume_versions (organization_id, candidate_id, version_number DESC);
CREATE INDEX idx_jobmatch_org_ver_score ON job_matches (organization_id, job_version_id, score DESC);
CREATE INDEX idx_app_org_cand ON applications (organization_id, candidate_id, created_at DESC);
CREATE INDEX idx_app_org_job ON applications (organization_id, job_id, created_at DESC);
CREATE INDEX idx_inbmsg_org_received ON inbound_messages (organization_id, received_at DESC);

-- -----------------------------------------------
-- 25. Outbox Events
-- -----------------------------------------------
CREATE TABLE outbox_events (
    id              UUID         NOT NULL,
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         TEXT,
    status          VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    TIMESTAMP,
    CONSTRAINT pk_outbox_events PRIMARY KEY (id)
);

-- -----------------------------------------------
-- 26. Audit Events
-- -----------------------------------------------
CREATE TABLE audit_events (
    id              UUID         NOT NULL,
    organization_id UUID,
    user_id         UUID,
    action          VARCHAR(100) NOT NULL,
    entity_type     VARCHAR(100),
    entity_id       UUID,
    details         TEXT,
    ip_address      VARCHAR(45),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_audit_events PRIMARY KEY (id)
);

-- -----------------------------------------------
-- 27. AI Usage Events
-- -----------------------------------------------
CREATE TABLE ai_usage_events (
    id              UUID         NOT NULL,
    organization_id UUID,
    provider        VARCHAR(50),
    model           VARCHAR(100),
    input_tokens    INT,
    output_tokens   INT,
    latency_ms      BIGINT,
    resume_file_id  UUID,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_ai_usage_events PRIMARY KEY (id),
    CONSTRAINT fk_aiusage_resume FOREIGN KEY (resume_file_id) REFERENCES resume_files(id)
);

