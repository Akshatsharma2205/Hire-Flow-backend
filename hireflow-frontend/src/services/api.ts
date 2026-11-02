import {
  AuthResponse,
  HealthMetrics,
  ReviewCase,
  Candidate,
  CandidateSkill,
  ResumeVersionDto,
  JobDto,
  JobMatchDto,
  ApplicationDto,
  ApplicationHistoryDto,
  MailConnectionDto,
  EmailRuleDto,
  RulePreviewRequest,
  RulePreviewResponse,
  InboundMessageDto,
  WebhookCredentialDto,
} from '../types';

let currentAuthToken: string | null = null;
let onUnauthorizedCallback: (() => void) | null = null;

export const setAuthToken = (token: string | null) => {
  currentAuthToken = token;
};

export const getAuthToken = () => currentAuthToken;

export const setOnUnauthorizedCallback = (callback: () => void) => {
  onUnauthorizedCallback = callback;
};

async function request<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers || {});

  if (currentAuthToken && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${currentAuthToken}`);
  }

  if (!(options.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  const response = await fetch(endpoint, {
    ...options,
    headers,
  });

  if (response.status === 401) {
    if (onUnauthorizedCallback) {
      onUnauthorizedCallback();
    }
    const err = await response.json().catch(() => ({ error: 'Unauthorized' }));
    throw new Error(err.message || err.error || 'Authentication required');
  }

  if (!response.ok) {
    const errorBody = await response.json().catch(() => null);
    const msg = errorBody?.message || errorBody?.error || `HTTP ${response.status}: ${response.statusText}`;
    throw new Error(msg);
  }

  if (response.status === 204) {
    return {} as T;
  }

  const contentType = response.headers.get('content-type');
  if (contentType && contentType.includes('application/json')) {
    return response.json();
  }

  return response.text() as unknown as T;
}

export const api = {
  // Auth
  async login(payload: { email: string; password: string }): Promise<AuthResponse> {
    const data = await request<AuthResponse>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
    setAuthToken(data.token);
    return data;
  },

  async getExtensionToken(): Promise<{ extensionToken: string }> {
    return request('/api/v1/auth/extension-token', { method: 'POST' });
  },

  // Operations / Health & Review Cases
  async getHealth(): Promise<HealthMetrics> {
    return request('/api/v1/operations/health');
  },

  async getReviewCases(): Promise<ReviewCase[]> {
    return request('/api/v1/review-cases');
  },

  async resolveReviewCase(
    id: string,
    payload: { action: string; targetJobId?: string; notes?: string }
  ): Promise<any> {
    return request(`/api/v1/review-cases/${id}/resolve`, {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },

  // Candidates
  async getCandidates(
    page: number = 0,
    size: number = 25,
    search?: string,
    jobId?: string,
    sourceType?: string
  ): Promise<{ content: Candidate[]; totalPages: number; totalElements: number }> {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
    });
    if (search && search.trim()) {
      params.set('search', search.trim());
    }
    if (jobId && jobId.trim()) {
      params.set('jobId', jobId.trim());
    }
    if (sourceType && sourceType.trim()) {
      params.set('sourceType', sourceType.trim());
    }
    return request(`/api/v1/candidates?${params.toString()}`);
  },

  async getCandidate(id: string): Promise<Candidate> {
    return request(`/api/v1/candidates/${id}`);
  },

  async getCandidateVersions(id: string): Promise<ResumeVersionDto[]> {
    return request(`/api/v1/candidates/${id}/versions`);
  },

  async getCandidateApplications(id: string): Promise<ApplicationDto[]> {
    return request(`/api/v1/candidates/${id}/applications`);
  },

  async getResumeViewUrl(versionId: string): Promise<{ url: string }> {
    return request(`/api/v1/resume-versions/${versionId}/view-url`);
  },

  async uploadDirectResume(file: File, sourceType: string = 'UPLOAD'): Promise<any> {
    const formData = new FormData();
    formData.append('files', file);
    formData.append('sourceType', sourceType);
    return request('/api/v1/uploads/direct', {
      method: 'POST',
      body: formData,
    });
  },

  async extensionIntake(file: File, candidateData?: Record<string, any>): Promise<any> {
    const formData = new FormData();
    formData.append('file', file);
    if (candidateData) {
      formData.append('candidateData', JSON.stringify(candidateData));
    }
    return request('/api/v1/uploads/extension-intake', {
      method: 'POST',
      body: formData,
    });
  },

  async checkHashes(sha256List: string[]): Promise<{ existingHashes: string[] }> {
    return request('/api/v1/uploads/check-hashes', {
      method: 'POST',
      body: JSON.stringify({ sha256List }),
    });
  },

  // Jobs
  async getJobs(): Promise<JobDto[]> {
    return request('/api/v1/jobs');
  },

  async getJob(id: string): Promise<JobDto> {
    return request(`/api/v1/jobs/${id}`);
  },

  async createJob(payload: {
    title: string;
    department?: string;
    location?: string;
    minExperienceMonths?: number;
    maxExperienceMonths?: number;
    description: string;
  }): Promise<JobDto> {
    return request('/api/v1/jobs', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },

  async updateJobStatus(id: string, status: string): Promise<JobDto> {
    return request(`/api/v1/jobs/${id}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status }),
    });
  },

  async createJobVersion(id: string, description: string): Promise<any> {
    return request(`/api/v1/jobs/${id}/versions`, {
      method: 'POST',
      body: JSON.stringify({ description }),
    });
  },

  async getJobMatches(jobId: string): Promise<JobMatchDto[]> {
    return request(`/api/v1/jobs/${jobId}/matches`);
  },

  // Applications
  async getApplications(): Promise<ApplicationDto[]> {
    return request('/api/v1/applications');
  },

  async createApplication(payload: {
    candidateId: string;
    jobId: string;
    resumeVersionId?: string;
  }): Promise<ApplicationDto> {
    return request('/api/v1/applications', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },

  async updateApplicationStage(
    id: string,
    stage: string,
    note?: string
  ): Promise<ApplicationDto> {
    return request(`/api/v1/applications/${id}/stage`, {
      method: 'PATCH',
      body: JSON.stringify({ stage, note }),
    });
  },

  async getApplicationHistory(id: string): Promise<ApplicationHistoryDto[]> {
    return request(`/api/v1/applications/${id}/history`);
  },

  // Mail Connections
  async getMailConnections(): Promise<MailConnectionDto[]> {
    return request('/api/v1/mail-connections');
  },

  async createImapConnection(payload: {
    host: string;
    port: number;
    username: string;
    password: string;
    folderFilter?: string;
    ssl?: boolean;
  }): Promise<MailConnectionDto> {
    return request('/api/v1/mail-connections', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },

  async disconnectMailbox(id: string): Promise<any> {
    return request(`/api/v1/mail-connections/${id}`, {
      method: 'DELETE',
    });
  },

  async syncNow(id: string): Promise<any> {
    return request(`/api/v1/mail-connections/${id}/sync-now`, {
      method: 'POST',
    });
  },

  async getOAuthAuthorizeUrl(provider: 'google' | 'microsoft'): Promise<{ authorizationUrl: string }> {
    return request(`/api/v1/mail-connections/oauth/${provider}/authorize`);
  },

  // Recruitment Rules
  async getRules(): Promise<EmailRuleDto[]> {
    return request('/api/v1/rules');
  },

  async getRule(id: string): Promise<EmailRuleDto> {
    return request(`/api/v1/rules/${id}`);
  },

  async createRule(payload: Partial<EmailRuleDto>): Promise<EmailRuleDto> {
    return request('/api/v1/rules', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },

  async updateRule(id: string, payload: Partial<EmailRuleDto>): Promise<EmailRuleDto> {
    return request(`/api/v1/rules/${id}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  },

  async toggleRuleStatus(id: string, enabled: boolean): Promise<EmailRuleDto> {
    return request(`/api/v1/rules/${id}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ enabled }),
    });
  },

  async deleteRule(id: string): Promise<any> {
    return request(`/api/v1/rules/${id}`, {
      method: 'DELETE',
    });
  },

  async reorderRules(ruleIds: string[]): Promise<any> {
    return request('/api/v1/rules/reorder', {
      method: 'POST',
      body: JSON.stringify({ ruleIds }),
    });
  },

  async previewRule(payload: RulePreviewRequest): Promise<RulePreviewResponse> {
    return request('/api/v1/rules/preview', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },

  // Activity / Messages
  async getInboundMessages(status?: string, connectionId?: string): Promise<InboundMessageDto[]> {
    const params = new URLSearchParams();
    if (status && status !== 'ALL') params.set('status', status);
    if (connectionId) params.set('connectionId', connectionId);
    const query = params.toString() ? `?${params.toString()}` : '';
    return request(`/api/v1/operations/messages${query}`);
  },

  async getInboundMessage(id: string): Promise<InboundMessageDto> {
    return request(`/api/v1/operations/messages/${id}`);
  },

  async retryMessage(id: string): Promise<InboundMessageDto> {
    return request(`/api/v1/operations/messages/${id}/retry`, {
      method: 'POST',
    });
  },

  // Settings / Webhook Credentials
  async getWebhookCredentials(): Promise<WebhookCredentialDto[]> {
    return request('/api/v1/webhook-credentials');
  },

  async createWebhookCredential(description: string): Promise<WebhookCredentialDto> {
    return request('/api/v1/webhook-credentials', {
      method: 'POST',
      body: JSON.stringify({ description }),
    });
  },

  async rotateWebhookCredential(id: string): Promise<WebhookCredentialDto> {
    return request(`/api/v1/webhook-credentials/${id}/rotate`, {
      method: 'POST',
    });
  },

  async revokeWebhookCredential(id: string): Promise<any> {
    return request(`/api/v1/webhook-credentials/${id}/revoke`, {
      method: 'POST',
    });
  },
};
