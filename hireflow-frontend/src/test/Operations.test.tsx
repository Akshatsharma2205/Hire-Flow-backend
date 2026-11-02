import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { DashboardPage } from '../pages/DashboardPage';
import { MailboxesPage } from '../pages/MailboxesPage';
import { ActivityPage } from '../pages/ActivityPage';
import { SettingsPage } from '../pages/SettingsPage';
import { ThemeProvider } from '../context/ThemeContext';
import { AuthProvider } from '../context/AuthContext';
import { ToastProvider } from '../components/common/Toast';
import { api } from '../services/api';

import {
  HealthMetrics,
  ReviewCase,
  MailConnectionDto,
  InboundMessageDto,
  WebhookCredentialDto,
} from '../types';

vi.mock('../services/api', () => ({
  api: {
    getHealth: vi.fn(),
    getReviewCases: vi.fn(),
    resolveReviewCase: vi.fn(),
    uploadDirectResume: vi.fn(),
    getMailConnections: vi.fn(),
    getOAuthAuthorizeUrl: vi.fn(),
    syncNow: vi.fn(),
    disconnectMailbox: vi.fn(),
    createImapConnection: vi.fn(),
    getExtensionToken: vi.fn(),
    checkHashes: vi.fn(),
    extensionIntake: vi.fn(),
    getInboundMessages: vi.fn(),
    retryMessage: vi.fn(),
    getWebhookCredentials: vi.fn(),
    createWebhookCredential: vi.fn(),
    rotateWebhookCredential: vi.fn(),
    revokeWebhookCredential: vi.fn(),
    login: vi.fn(),
    getSystemHealth: vi.fn(),
  },
  setAuthToken: vi.fn(),
  setOnUnauthorizedCallback: vi.fn(),
}));

const mockUser = {
  token: 'mock-token-xyz',
  email: 'hr@kartgen.com',
  displayName: 'Kartgen HR Admin',
  role: 'OWNER',
  organizationId: '11111111-1111-1111-1111-111111111111',
  organizationName: 'Kartgen Infotech',
  authenticated: true,
};

const mockHealthMetrics: HealthMetrics = {
  queueDepth: 3,
  runningCount: 2,
  processedCount: 42,
  failedCount: 0,
  totalResumeFiles: 45,
  totalCandidates: 38,
  activeMailConnections: 2,
  aiTokenSpend: 15400,
};

const mockReviewCases: ReviewCase[] = [
  {
    id: 'case-uuid-12345678',
    organizationId: '11111111-1111-1111-1111-111111111111',
    reasonCode: 'AMBIGUOUS_JOB_MAPPING',
    details: 'Candidate skills match multiple open positions.',
    status: 'PENDING',
    createdAt: new Date('2026-11-01T10:00:00Z').toISOString(),
  },
];

const mockMailConnections: MailConnectionDto[] = [
  {
    id: 'conn-google-1',
    organizationId: '11111111-1111-1111-1111-111111111111',
    provider: 'GOOGLE',
    emailAddress: 'careers@kartgen.com',
    folderFilter: 'INBOX',
    status: 'ACTIVE',
    lastSyncAt: new Date('2026-11-02T12:00:00Z').toISOString(),
    createdAt: new Date('2026-10-01T00:00:00Z').toISOString(),
  },
  {
    id: 'conn-ms-2',
    organizationId: '11111111-1111-1111-1111-111111111111',
    provider: 'MICROSOFT',
    emailAddress: 'jobs@kartgen.com',
    folderFilter: 'Recruitment',
    status: 'REAUTH',
    lastSyncAt: new Date('2026-11-02T11:00:00Z').toISOString(),
    createdAt: new Date('2026-10-05T00:00:00Z').toISOString(),
  },
];

const mockInboundMessages: InboundMessageDto[] = [
  {
    id: 'msg-uuid-98765432',
    senderEmail: 'applicant@example.com',
    senderName: 'Jane Doe',
    subject: 'Senior Fullstack Engineer Application',
    sourceType: 'EMAIL',
    processingStatus: 'COMPLETED',
    receivedAt: new Date('2026-11-02T14:30:00Z').toISOString(),
    createdAt: new Date('2026-11-02T14:30:00Z').toISOString(),
    ruleAction: 'ACCEPT_AND_PARSE',
    ruleExplanation: 'Matches active job requisition and valid PDF resume attached.',
    attachments: [
      {
        id: 'att-1',
        originalFilename: 'Jane_Doe_Resume.pdf',
        detectedMime: 'application/pdf',
        sizeBytes: 154200,
        status: 'PARSED',
      },
    ],
  },
];

const mockWebhookCredentials: WebhookCredentialDto[] = [
  {
    keyId: 'hf_wh_prod_key_01',
    id: 'hf_wh_prod_key_01',
    description: 'Production Inbound Mail Webhook',
    status: 'ACTIVE',
    createdAt: new Date('2026-10-15T00:00:00Z').toISOString(),
  },
];

const renderWithProviders = (ui: React.ReactElement) => {
  return render(
    <MemoryRouter>
      <ThemeProvider>
        <AuthProvider>
          <ToastProvider>{ui}</ToastProvider>
        </AuthProvider>
      </ThemeProvider>
    </MemoryRouter>
  );
};

describe('Operations Workspace & Dashboards (M3-UI)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    document.documentElement.className = '';
    vi.mocked(api.login).mockResolvedValue(mockUser);
    vi.mocked(api.getSystemHealth).mockResolvedValue({ status: 'UP' });
    vi.mocked(api.getHealth).mockResolvedValue(mockHealthMetrics);
    vi.mocked(api.getReviewCases).mockResolvedValue(mockReviewCases);
    vi.mocked(api.getMailConnections).mockResolvedValue(mockMailConnections);
    vi.mocked(api.getInboundMessages).mockResolvedValue(mockInboundMessages);
    vi.mocked(api.getWebhookCredentials).mockResolvedValue(mockWebhookCredentials);
  });

  describe('1. Dashboard Workspace (Features 12, 13, 14)', () => {
    it('renders KPI metrics hierarchy, operational insight widgets, and pending review cases', async () => {
      renderWithProviders(<DashboardPage />);

      expect(screen.getByText(/Recruitment Operations/i)).toBeInTheDocument();

      await waitFor(() => {
        expect(screen.getByText('38')).toBeInTheDocument(); // Total candidates
        expect(screen.getByText('45')).toBeInTheDocument(); // Resume files
        expect(screen.getByText('3')).toBeInTheDocument();  // Queue depth
        expect(screen.getByText('42')).toBeInTheDocument(); // Processed
        expect(screen.getByText('15,400')).toBeInTheDocument(); // AI token spend
      });

      // Operational insight widgets
      expect(screen.getByText(/Pipeline Velocity & Status/i)).toBeInTheDocument();
      expect(screen.getByText(/2 processing/i)).toBeInTheDocument();
      expect(screen.getByText(/Live Mailbox Health/i)).toBeInTheDocument();
      expect(screen.getByText(/Quick Operations/i)).toBeInTheDocument();

      // Pending review case
      expect(screen.getByText(/AMBIGUOUS_JOB_MAPPING/i)).toBeInTheDocument();
      expect(screen.getByText(/case-uui/i)).toBeInTheDocument();
    });

    it('renders compact clean state when review queue is empty', async () => {
      vi.mocked(api.getReviewCases).mockResolvedValue([]);
      renderWithProviders(<DashboardPage />);

      await waitFor(() => {
        expect(screen.getByText('Review Queue Cleared')).toBeInTheDocument();
        expect(
          screen.getByText('All candidate extractions and rule evaluations matched deterministically.')
        ).toBeInTheDocument();
      });
    });

    it('resolves review case via resolution modal', async () => {
      vi.mocked(api.resolveReviewCase).mockResolvedValue({ success: true } as any);
      renderWithProviders(<DashboardPage />);

      await waitFor(() => {
        expect(screen.getByText(/Resolve/i)).toBeInTheDocument();
      });

      // Click Resolve button
      fireEvent.click(screen.getByRole('button', { name: /^Resolve$/i }));

      // Modal opens
      await waitFor(() => {
        expect(screen.getByText(/Resolve Identity Review Case/i)).toBeInTheDocument();
        expect(screen.getByText(/Conflict Description:/i)).toBeInTheDocument();
      });

      // Add recruiter note and submit
      const notesInput = screen.getByPlaceholderText(/Audit notes for this resolution decision/i);
      fireEvent.change(notesInput, { target: { value: 'Confirmed candidate match with requisition.' } });

      const confirmBtn = screen.getByRole('button', { name: /Confirm Resolution/i });
      fireEvent.click(confirmBtn);

      await waitFor(() => {
        expect(api.resolveReviewCase).toHaveBeenCalledWith('case-uuid-12345678', {
          action: 'MERGE',
          notes: 'Confirmed candidate match with requisition.',
        });
      });
    });

    it('opens direct resume upload modal and queues file', async () => {
      vi.mocked(api.uploadDirectResume).mockResolvedValue({ id: 'res-1' } as any);
      renderWithProviders(<DashboardPage />);

      const uploadBtn = screen.getByRole('button', { name: /Upload Resumes/i });
      fireEvent.click(uploadBtn);

      await waitFor(() => {
        expect(screen.getByText('Direct Resume File Intake')).toBeInTheDocument();
      });

      const file = new File(['dummy content'], 'candidate_cv.pdf', { type: 'application/pdf' });
      const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;
      expect(fileInput).not.toBeNull();

      Object.defineProperty(fileInput, 'files', {
        value: [file],
      });
      fireEvent.change(fileInput);

      await waitFor(() => {
        expect(screen.getByText(/Selected/i)).toBeInTheDocument();
      });

      const queueBtn = screen.getByRole('button', { name: /Queue for Extraction/i });
      fireEvent.click(queueBtn);

      await waitFor(() => {
        expect(api.uploadDirectResume).toHaveBeenCalledWith(file, 'UPLOAD');
      });
    });
  });

  describe('2. Mailboxes Workspace (Feature 15)', () => {
    it('renders cloud provider cards and active connection list', async () => {
      renderWithProviders(<MailboxesPage />);

      expect(screen.getByText(/Mailbox Integration & Intake/i)).toBeInTheDocument();
      expect(screen.getAllByText(/Google Workspace/i).length).toBeGreaterThan(0);
      expect(screen.getAllByText(/Microsoft 365/i).length).toBeGreaterThan(0);

      await waitFor(() => {
        expect(screen.getByText('careers@kartgen.com')).toBeInTheDocument();
        expect(screen.getByText('jobs@kartgen.com')).toBeInTheDocument();
      });
    });

    it('renders inline Reauthorize button for REAUTH status and initiates OAuth flow', async () => {
      vi.spyOn(window, 'open').mockReturnValue({ focus: vi.fn(), closed: false } as any);
      vi.mocked(api.getOAuthAuthorizeUrl).mockResolvedValue({
        authorizationUrl: 'https://login.microsoftonline.com/common/oauth2/v2.0/authorize?mock=1',
      });

      renderWithProviders(<MailboxesPage />);

      await waitFor(() => {
        expect(screen.getByText('jobs@kartgen.com')).toBeInTheDocument();
      });

      // The connection with status REAUTH renders Reauthorize button
      const reauthBtn = screen.getByRole('button', { name: /Reauthorize/i });
      expect(reauthBtn).toBeInTheDocument();

      fireEvent.click(reauthBtn);

      await waitFor(() => {
        expect(api.getOAuthAuthorizeUrl).toHaveBeenCalledWith('microsoft');
        expect(window.open).toHaveBeenCalled();
      });
    });

    it('triggers incremental mailbox sync on Sync Now click', async () => {
      vi.mocked(api.syncNow).mockResolvedValue({ count: 5 } as any);
      renderWithProviders(<MailboxesPage />);

      await waitFor(() => {
        expect(screen.getByText('careers@kartgen.com')).toBeInTheDocument();
      });

      const syncButtons = screen.getAllByRole('button', { name: /Sync Now/i });
      expect(syncButtons.length).toBeGreaterThan(0);
      fireEvent.click(syncButtons[0]);

      await waitFor(() => {
        expect(api.syncNow).toHaveBeenCalledWith('conn-google-1');
      });
    });

    it('toggles custom IMAP form accordion', async () => {
      renderWithProviders(<MailboxesPage />);

      const accordionBtn = screen.getByText(/Custom IMAP \/ On-Premise Mail Server Setup/i);
      expect(screen.queryByText(/IMAP Host/i)).not.toBeInTheDocument();

      fireEvent.click(accordionBtn);

      expect(screen.getByText(/IMAP Host/i)).toBeInTheDocument();
      expect(screen.getByText(/App Password/i)).toBeInTheDocument();
    });
  });

  describe('3. Activity Workspace (Feature 16)', () => {
    it('renders processing lifecycle pipeline stages and inbound telemetry table', async () => {
      renderWithProviders(<ActivityPage />);

      expect(screen.getByText(/Processing Activity & Lifecycle/i)).toBeInTheDocument();
      expect(screen.getByText(/1\. Discovered/i)).toBeInTheDocument();
      expect(screen.getByText(/2\. Downloading/i)).toBeInTheDocument();
      expect(screen.getByText(/3\. Validating/i)).toBeInTheDocument();
      expect(screen.getByText(/4\. Extracting/i)).toBeInTheDocument();
      expect(screen.getByText(/5\. Matching/i)).toBeInTheDocument();
      expect(screen.getByText(/6\. Completed/i)).toBeInTheDocument();

      await waitFor(() => {
        expect(screen.getByText('Jane Doe')).toBeInTheDocument();
        expect(screen.getByText(/Senior Fullstack Engineer Application/i)).toBeInTheDocument();
      });
    });

    it('filters messages by status dropdown', async () => {
      renderWithProviders(<ActivityPage />);

      await waitFor(() => {
        expect(api.getInboundMessages).toHaveBeenCalledWith('ALL');
      });

      const select = screen.getByRole('combobox');
      fireEvent.change(select, { target: { value: 'COMPLETED' } });

      await waitFor(() => {
        expect(api.getInboundMessages).toHaveBeenCalledWith('COMPLETED');
      });
    });

    it('inspects message details and triggers re-queue retry', async () => {
      vi.mocked(api.retryMessage).mockResolvedValue({ success: true } as any);
      renderWithProviders(<ActivityPage />);

      await waitFor(() => {
        expect(screen.getByText('Jane Doe')).toBeInTheDocument();
      });

      // Click on row to open modal
      fireEvent.click(screen.getByText('Jane Doe'));

      await waitFor(() => {
        expect(screen.getByText(/Message Extraction Inspector/i)).toBeInTheDocument();
        expect(screen.getByText(/Rule Engine Evaluation/i)).toBeInTheDocument();
        expect(screen.getByText(/Jane_Doe_Resume\.pdf/i)).toBeInTheDocument();
      });

      const retryBtn = screen.getByRole('button', { name: /Re-queue Message/i });
      fireEvent.click(retryBtn);

      await waitFor(() => {
        expect(api.retryMessage).toHaveBeenCalledWith('msg-uuid-98765432');
      });
    });
  });

  describe('4. Settings Workspace (Feature 17)', () => {
    it('renders organization identity profile and webhook credentials', async () => {
      renderWithProviders(<SettingsPage />);

      expect(screen.getByText(/Organization & Security Settings/i)).toBeInTheDocument();
      await waitFor(() => {
        expect(screen.getByText('Kartgen Infotech')).toBeInTheDocument();
        expect(screen.getByText(/Tenant ID: 11111111-1111-1111-1111-111111111111/i)).toBeInTheDocument();
      });

      await waitFor(() => {
        expect(screen.getByText('hf_wh_prod_key_01')).toBeInTheDocument();
      });
    });

    it('renders Appearance & Theme section and toggles theme', async () => {
      renderWithProviders(<SettingsPage />);

      expect(screen.getByText(/Appearance & Theme/i)).toBeInTheDocument();
      expect(screen.getByText(/Enterprise Light \(Default\)/i)).toBeInTheDocument();
      expect(screen.getByText(/Obsidian Dark/i)).toBeInTheDocument();

      const darkCard = screen.getByTestId('theme-dark-card');
      fireEvent.click(darkCard);

      expect(document.documentElement.classList.contains('dark')).toBe(true);
      expect(localStorage.getItem('hireflow_theme')).toBe('dark');

      const lightCard = screen.getByTestId('theme-light-card');
      fireEvent.click(lightCard);

      expect(document.documentElement.classList.contains('dark')).toBe(false);
      expect(localStorage.getItem('hireflow_theme')).toBe('light');
    });

    it('generates new webhook signing key and opens secret display modal', async () => {
      vi.mocked(api.createWebhookCredential).mockResolvedValue({
        keyId: 'hf_wh_new_key_99',
        secret: 'whsec_super_secret_test_token_123',
        description: 'Production Inbound Forwarder',
        status: 'ACTIVE',
        createdAt: new Date().toISOString(),
      });

      renderWithProviders(<SettingsPage />);

      const generateBtn = screen.getByRole('button', { name: /Generate Signing Key/i });
      fireEvent.click(generateBtn);

      await waitFor(() => {
        expect(api.createWebhookCredential).toHaveBeenCalled();
        expect(screen.getByText(/Save Webhook Secret Key/i)).toBeInTheDocument();
        expect(screen.getByText('whsec_super_secret_test_token_123')).toBeInTheDocument();
      });

      const dismissBtn = screen.getByRole('button', { name: /I Have Stored the Secret/i });
      fireEvent.click(dismissBtn);

      expect(screen.queryByText(/Save Webhook Secret Key/i)).not.toBeInTheDocument();
    });
  });
});
