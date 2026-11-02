import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import React from 'react';
import { BrowserRouter } from 'react-router-dom';
import { DashboardPage } from '../pages/DashboardPage';
import { ToastProvider } from '../components/common/Toast';
import { api } from '../services/api';

vi.mock('../services/api', () => ({
  api: {
    getHealth: vi.fn().mockResolvedValue({
      queueDepth: 3,
      runningCount: 1,
      processedCount: 42,
      failedCount: 0,
      totalResumeFiles: 45,
      totalCandidates: 38,
      activeMailConnections: 2,
      aiTokenSpend: 15400,
    }),
    getReviewCases: vi.fn().mockResolvedValue([
      {
        id: 'case-uuid-12345678',
        organizationId: '11111111-1111-1111-1111-111111111111',
        reasonCode: 'AMBIGUOUS_JOB_MAPPING',
        details: 'Candidate skills match multiple open positions.',
        status: 'PENDING',
        createdAt: new Date().toISOString(),
      },
    ]),
  },
}));

describe('Dashboard Page Telemetry & Review Queue', () => {
  it('renders genuine KPI metrics and review cases', async () => {
    render(
      <BrowserRouter>
        <ToastProvider>
          <DashboardPage />
        </ToastProvider>
      </BrowserRouter>
    );

    expect(screen.getByText(/Recruitment Operations/i)).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByText('38')).toBeInTheDocument(); // total candidates
      expect(screen.getByText('45')).toBeInTheDocument(); // resume files
      expect(screen.getByText('3')).toBeInTheDocument();  // queue depth
      expect(screen.getByText('42')).toBeInTheDocument(); // processed
    });

    expect(screen.getByText(/AMBIGUOUS_JOB_MAPPING/i)).toBeInTheDocument();
    expect(screen.getByText(/case-uui/i)).toBeInTheDocument();
  });
});
