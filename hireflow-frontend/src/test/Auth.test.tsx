import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import React from 'react';
import { BrowserRouter } from 'react-router-dom';
import { LoginPage } from '../pages/LoginPage';
import { AuthProvider } from '../context/AuthContext';
import { ToastProvider } from '../components/common/Toast';
import { api } from '../services/api';

vi.mock('../services/api', () => ({
  api: {
    login: vi.fn(),
  },
  setAuthToken: vi.fn(),
  setOnUnauthorizedCallback: vi.fn(),
}));

describe('Authentication & Login Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Default bootstrap auto-login fails so login form is rendered
    vi.mocked(api.login).mockRejectedValue(new Error('No auto session'));
  });

  it('renders login form and demo credentials button', async () => {
    render(
      <BrowserRouter>
        <AuthProvider>
          <ToastProvider>
            <LoginPage />
          </ToastProvider>
        </AuthProvider>
      </BrowserRouter>
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /Sign in to/i })).toBeInTheDocument();
      expect(screen.getByPlaceholderText(/hr@kartgen.com/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /One-Click Demo Sign In/i })).toBeInTheDocument();
    });
  });

  it('triggers quick demo login with hr@kartgen.com', async () => {
    render(
      <BrowserRouter>
        <AuthProvider>
          <ToastProvider>
            <LoginPage />
          </ToastProvider>
        </AuthProvider>
      </BrowserRouter>
    );

    const demoBtn = await screen.findByRole('button', { name: /One-Click Demo Sign In/i });
    
    vi.mocked(api.login).mockResolvedValueOnce({
      token: 'jwt-mock-demo-token',
      email: 'hr@kartgen.com',
      role: 'OWNER',
      organizationId: '11111111-1111-1111-1111-111111111111',
      organizationName: 'Kartgen Infotech LLP',
    });

    fireEvent.click(demoBtn);

    await waitFor(() => {
      expect(api.login).toHaveBeenCalledWith({
        email: 'hr@kartgen.com',
        password: 'password123',
      });
    });
  });

  it('keeps the form available for an explicit account switch', async () => {
    window.history.pushState({}, '', '/login?reauth=1');

    vi.mocked(api.login).mockResolvedValue({
      token: 'jwt-mock-existing-session',
      email: 'hr@kartgen.com',
      displayName: 'Kartgen HR Admin',
      role: 'OWNER',
      organizationId: '11111111-1111-1111-1111-111111111111',
      organizationName: 'Kartgen Infotech LLP',
    });

    render(
      <BrowserRouter>
        <AuthProvider>
          <ToastProvider>
            <LoginPage />
          </ToastProvider>
        </AuthProvider>
      </BrowserRouter>
    );

    expect(await screen.findByText(/Currently signed in as/i)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /Sign in to/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /One-Click Demo Sign In/i })).toBeInTheDocument();

    window.history.pushState({}, '', '/');
  });
});
