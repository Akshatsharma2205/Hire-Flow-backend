-- =====================================================================
-- V3__performance_indices.sql – Query Performance & Lookup Indices
-- =====================================================================

-- 1. Extension token lookup index (crucial for AuthFilter)
CREATE INDEX IF NOT EXISTS idx_orgusers_token_hash ON organization_users (extension_token_hash);

-- 2. Candidate skill lookups
CREATE INDEX IF NOT EXISTS idx_candskill_cand_prof ON candidate_skills (candidate_id, profile_version_id);
CREATE INDEX IF NOT EXISTS idx_candskill_org_cand ON candidate_skills (organization_id, candidate_id);
CREATE INDEX IF NOT EXISTS idx_candskill_profver ON candidate_skills (profile_version_id);

-- 3. Job match lookups
CREATE INDEX IF NOT EXISTS idx_jobmatch_cand ON job_matches (candidate_id);
CREATE INDEX IF NOT EXISTS idx_jobmatch_ver_score ON job_matches (job_version_id, score DESC);

-- 4. Candidate profile version ordering
CREATE INDEX IF NOT EXISTS idx_cpv_cand_ver ON candidate_profile_versions (candidate_id, profile_version DESC);

-- 5. Inbound attachments
CREATE INDEX IF NOT EXISTS idx_inbatt_org_status ON inbound_attachments (organization_id, status);
CREATE INDEX IF NOT EXISTS idx_inbatt_resumefile ON inbound_attachments (resume_file_id);

-- 6. Jobs & Job Versions
CREATE INDEX IF NOT EXISTS idx_jobs_org_status ON jobs (organization_id, status);
CREATE INDEX IF NOT EXISTS idx_jobver_job_ver ON job_versions (job_id, version_number DESC);
CREATE INDEX IF NOT EXISTS idx_jsr_jobver ON job_skill_requirements (job_version_id);

-- 7. Applications & Stage History
CREATE INDEX IF NOT EXISTS idx_appsub_app ON application_submissions (application_id);
CREATE INDEX IF NOT EXISTS idx_appstage_app ON application_stage_history (application_id, changed_at DESC);
CREATE INDEX IF NOT EXISTS idx_mailconn_org_status ON mail_connections (organization_id, status);
