export interface AuthResponse {
  token: string;
  email: string;
  displayName?: string;
  organizationId: string;
  organizationName?: string;
  role: string;
}

export interface UserSession extends AuthResponse {
  authenticated: boolean;
}

export interface HealthMetrics {
  queueDepth: number;
  runningCount: number;
  processedCount: number;
  failedCount: number;
  totalResumeFiles: number;
  totalCandidates: number;
  activeMailConnections: number;
  aiTokenSpend: number;
}

export interface ReviewCase {
  id: string;
  organizationId: string;
  candidateId?: string | null;
  reasonCode: string;
  details?: string;
  status: 'PENDING' | 'RESOLVED' | 'DISMISSED';
  resolvedAt?: string | null;
  createdAt: string;
}

export interface CandidateSkill {
  name: string;
  category?: string;
  confidence?: number;
  yearsExperience?: number;
}

export interface Candidate {
  id: string;
  displayName: string;
  primaryEmail: string;
  primaryPhone?: string;
  currentCity?: string;
  currentExperienceMonths?: number;
  experienceConfidence?: string;
  status?: string;
  currentResumeVersionId?: string;
  topSkills?: string[];
  skills?: string[] | CandidateSkill[];
  matchScore?: number | null;
  sourceChannel?: string;
  sourceType?: string;
  resumeVersion?: number;
  currentStage?: string;
  lastApplicationAt?: string;
  createdAt?: string;
}

export interface ResumeVersionDto {
  id: string;
  candidateId: string;
  resumeFileId: string;
  versionNumber: number;
  parseStatus: string;
  validationStatus: string;
  extractedRawText?: string;
  createdAt: string;
}

export interface JobDto {
  id: string;
  organizationId?: string;
  title: string;
  department?: string;
  location?: string;
  status: 'ACTIVE' | 'CLOSED' | 'DRAFT';
  minExperienceMonths?: number;
  maxExperienceMonths?: number;
  description?: string;
  rawJobDescription?: string;
  versionCount?: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface JobMatchDto {
  candidateId: string;
  candidateName: string;
  candidateEmail?: string;
  overallScore: number;
  skillScore?: number;
  experienceScore?: number;
  roleScore?: number;
  matchingSkills?: string[];
  missingSkills?: string[];
}

export type ApplicationStage =
  | 'APPLIED'
  | 'SCREENING'
  | 'INTERVIEW'
  | 'OFFER'
  | 'HIRED'
  | 'REJECTED';

export interface ApplicationDto {
  id: string;
  candidateId: string;
  candidateName?: string;
  jobId: string;
  jobTitle?: string;
  resumeVersionId?: string;
  currentStage: string;
  attemptNumber?: number;
  sourceType?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface ApplicationHistoryDto {
  id: string;
  applicationId: string;
  fromStage: string;
  toStage: string;
  changedBy?: string;
  note?: string;
  createdAt: string;
}

export interface MailConnectionDto {
  id: string;
  organizationId?: string;
  provider: 'GOOGLE' | 'MICROSOFT' | 'IMAP';
  emailAddress: string;
  folderFilter?: string;
  status: 'ACTIVE' | 'REAUTH' | 'ERROR' | 'PAUSED';
  lastSyncAt?: string;
  errorMessage?: string;
  createdAt?: string;
}

export interface EmailRuleDto {
  id: string;
  organizationId?: string;
  name: string;
  description?: string;
  priority: number;
  enabled: boolean;
  connectionId?: string | null;
  folderFilter?: string | null;
  senderInclude?: string[];
  senderExclude?: string[];
  subjectKeywords?: string[];
  subjectExcludeKeywords?: string[];
  bodyKeywords?: string[];
  bodyExcludeKeywords?: string[];
  requireAttachment?: boolean;
  allowedMimes?: string[];
  minAttachmentSizeBytes?: number | null;
  maxAttachmentSizeBytes?: number | null;
  targetJobId?: string | null;
  duplicatePolicy?: string;
  actionOnMatch?: string;
  conditions?: Record<string, any>;
  createdAt?: string;
  updatedAt?: string;
}

export interface RulePreviewRequest {
  sender?: string;
  senderEmail?: string;
  subject?: string;
  body?: string;
  bodySnippet?: string;
  attachments?: {
    filename?: string;
    mimeType?: string;
    sizeBytes?: number;
  }[];
}

export interface RulePreviewResponse {
  matchedRuleId?: string;
  matchedRuleName?: string;
  targetJobId?: string;
  targetJobTitle?: string;
  action?: string;
  routeToReview?: boolean;
  reason?: string;
  explanation?: string;
  conditionsEvaluated?: {
    condition: string;
    passed: boolean;
    details?: string;
  }[];
}

export interface AttachmentSummaryDto {
  id: string;
  attachmentIndex?: number;
  originalFilename?: string;
  declaredMime?: string;
  detectedMime?: string;
  sizeBytes?: number;
  status?: string;
}

export interface InboundMessageDto {
  id: string;
  organizationId?: string;
  connectionId?: string;
  providerMessageId?: string;
  internetMessageId?: string;
  senderEmail?: string;
  senderName?: string;
  originalSenderClaim?: string;
  sourceType?: string;
  subject?: string;
  jobId?: string;
  receivedAt?: string;
  processingStatus: string;
  failureCode?: string;
  matchedRuleId?: string;
  ruleAction?: string;
  ruleExplanation?: string;
  attachments?: AttachmentSummaryDto[];
  createdAt: string;
}

export interface WebhookCredentialDto {
  id?: string;
  keyId: string;
  description?: string;
  status: 'ACTIVE' | 'REVOKED';
  secret?: string; // only returned upon creation/rotation
  createdAt: string;
  revokedAt?: string | null;
}
