import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { LandingPage } from '../pages/LandingPage';
import { ThemeProvider } from '../context/ThemeContext';
import { AuthProvider } from '../context/AuthContext';
import { api } from '../services/api';

vi.mock('../services/api', () => ({
  api: {
    login: vi.fn(),
  },
  setAuthToken: vi.fn(),
  setOnUnauthorizedCallback: vi.fn(),
}));

const renderLanding = () =>
  render(
    <MemoryRouter initialEntries={['/']}>
      <ThemeProvider>
        <AuthProvider>
          <LandingPage />
        </AuthProvider>
      </ThemeProvider>
    </MemoryRouter>
  );

describe('Public Landing Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    document.documentElement.className = '';
    vi.mocked(api.login).mockRejectedValue(new Error('No active session'));
  });

  it('renders the marketing hero and routes sign-in to /login', async () => {
    renderLanding();

    expect(
      screen.getByRole('heading', { name: /Every resume\. Every source\. One accountable pipeline\./i })
    ).toBeInTheDocument();
    expect(screen.getByText(/Candidate intake, resolved/i)).toBeInTheDocument();

    const signInLinks = screen.getAllByRole('link', { name: /Sign in to workspace/i });
    expect(signInLinks.length).toBeGreaterThan(0);
    signInLinks.forEach((link) => expect(link).toHaveAttribute('href', '/login'));
  });

  it('exposes the platform, workflow, and security sections', () => {
    const { container } = renderLanding();

    expect(container.querySelector('#platform')).not.toBeNull();
    expect(container.querySelector('#workflow')).not.toBeNull();
    expect(container.querySelector('#security')).not.toBeNull();
    expect(screen.getByText(/How it works/i)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /Security is not a/i })).toBeInTheDocument();
  });

  it('describes the intake workflow from capture to routing', () => {
    renderLanding();

    expect(screen.getByRole('heading', { name: 'Capture' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Resolve' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Route' })).toBeInTheDocument();
  });

  it('switches the primary action to the workspace when a session exists', async () => {
    vi.mocked(api.login).mockResolvedValue({
      token: 'jwt-mock',
      email: 'hr@kartgen.com',
      displayName: 'Kartgen HR Admin',
      role: 'OWNER',
      organizationId: '11111111-1111-1111-1111-111111111111',
      organizationName: 'Kartgen Infotech',
    });

    renderLanding();

    await waitFor(() => {
      const openWorkspace = screen.getAllByRole('link', { name: /Open workspace/i });
      expect(openWorkspace.length).toBeGreaterThan(0);
      openWorkspace.forEach((link) => expect(link).toHaveAttribute('href', '/dashboard'));
    });

    const signInLinks = screen.getAllByRole('link', { name: 'Sign in' });
    expect(signInLinks.length).toBeGreaterThan(0);
    signInLinks.forEach((link) => expect(link).toHaveAttribute('href', '/login?reauth=1'));
  });
});
