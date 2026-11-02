import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import React from 'react';
import { RulesPage } from '../pages/RulesPage';
import { ToastProvider } from '../components/common/Toast';
import { api } from '../services/api';

vi.mock('../services/api', () => ({
  api: {
    getRules: vi.fn().mockResolvedValue([
      {
        id: 'rule-uuid-1',
        name: 'Campus Engineering Ingestion',
        description: 'Auto-route university applicants to junior dev pool',
        priority: 10,
        enabled: true,
        subjectKeywords: ['resume', 'application'],
        duplicatePolicy: 'UPDATE_PROFILE_ONLY',
      },
    ]),
    getJobs: vi.fn().mockResolvedValue([]),
    getMailConnections: vi.fn().mockResolvedValue([]),
    previewRule: vi.fn().mockResolvedValue({
      matchedRuleId: 'rule-uuid-1',
      matchedRuleName: 'Campus Engineering Ingestion',
      targetJobTitle: 'Junior Developer',
      routeToReview: false,
      explanation: 'Matched sender university domain and subject keywords',
    }),
    reorderRules: vi.fn().mockResolvedValue({ status: 'SUCCESS' }),
  },
}));

describe('Recruitment Rules Page & Simulator', () => {
  it('renders configured email rules list', async () => {
    render(
      <ToastProvider>
        <RulesPage />
      </ToastProvider>
    );

    expect(screen.getByText(/Recruitment Email Rules/i)).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByText('Campus Engineering Ingestion')).toBeInTheDocument();
      expect(screen.getByText(/UPDATE_PROFILE_ONLY/i)).toBeInTheDocument();
    });
  });

  it('runs rule preview simulator and displays match explanation', async () => {
    render(
      <ToastProvider>
        <RulesPage />
      </ToastProvider>
    );

    const runBtn = screen.getByRole('button', { name: /Run Dry-Run Preview/i });
    fireEvent.click(runBtn);

    await waitFor(() => {
      expect(api.previewRule).toHaveBeenCalled();
      expect(screen.getByText(/Junior Developer/i)).toBeInTheDocument();
      expect(screen.getByText(/Matched sender university domain/i)).toBeInTheDocument();
    });
  });

  it('calls api.reorderRules when moving rule up or down', async () => {
    (api.getRules as any).mockResolvedValueOnce([
      { id: 'rule-1', name: 'Rule Alpha', priority: 1, enabled: true },
      { id: 'rule-2', name: 'Rule Beta', priority: 2, enabled: true },
    ]);

    render(
      <ToastProvider>
        <RulesPage />
      </ToastProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('Rule Alpha')).toBeInTheDocument();
      expect(screen.getByText('Rule Beta')).toBeInTheDocument();
    });

    const moveDownBtn = screen.getByRole('button', { name: /Move Rule Alpha down/i });
    fireEvent.click(moveDownBtn);

    await waitFor(() => {
      expect(api.reorderRules).toHaveBeenCalledWith(['rule-2', 'rule-1']);
    });
  });
});
