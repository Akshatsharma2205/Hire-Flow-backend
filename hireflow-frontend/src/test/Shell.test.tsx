import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import React from 'react';
import { MemoryRouter, Routes, Route, useNavigate } from 'react-router-dom';
import { Sidebar } from '../components/layout/Sidebar';
import { Header } from '../components/layout/Header';
import { AppLayout } from '../components/layout/AppLayout';
import { ThemeProvider } from '../context/ThemeContext';
import { AuthProvider, useAuth } from '../context/AuthContext';
import { api } from '../services/api';

vi.mock('../services/api', () => ({
  api: {
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

const renderWithProviders = (
  ui: React.ReactElement,
  { initialEntries = ['/dashboard'] } = {}
) => {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <ThemeProvider>
        <AuthProvider>{ui}</AuthProvider>
      </ThemeProvider>
    </MemoryRouter>
  );
};

describe('Application Shell & Navigation (M2-UI)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    document.documentElement.className = '';
    vi.mocked(api.login).mockResolvedValue(mockUser);
    vi.mocked(api.getSystemHealth).mockResolvedValue({ status: 'UP' });
  });

  describe('Sidebar Component', () => {
    it('renders all 9 navigation routes with names and icons', async () => {
      const onToggle = vi.fn();
      renderWithProviders(
        <Sidebar collapsed={false} onToggle={onToggle} />
      );

      await waitFor(() => {
        expect(screen.getByText('HireFlow')).toBeInTheDocument();
      });

      expect(screen.getByText('Dashboard')).toBeInTheDocument();
      expect(screen.getByText('Candidates')).toBeInTheDocument();
      expect(screen.getByText('Job Openings')).toBeInTheDocument();
      expect(screen.getByText('Applications')).toBeInTheDocument();
      expect(screen.getByText('Mailboxes')).toBeInTheDocument();
      expect(screen.getByText('Recruitment Rules')).toBeInTheDocument();
      expect(screen.getByText('Processing Activity')).toBeInTheDocument();
      expect(screen.getByText('Settings')).toBeInTheDocument();
      expect(screen.getByText('Documentation')).toBeInTheDocument();
    });

    it('highlights active route with brand teal token classes', async () => {
      renderWithProviders(
        <Sidebar collapsed={false} onToggle={vi.fn()} />,
        { initialEntries: ['/candidates'] }
      );

      await waitFor(() => {
        expect(screen.getByText('Candidates')).toBeInTheDocument();
      });

      const candidatesLink = screen.getByRole('link', { name: /Candidates/i });
      expect(candidatesLink.className).toContain('text-teal-700');
      expect(candidatesLink.className).toContain('bg-teal-50');

      const dashboardLink = screen.getByRole('link', { name: /Dashboard/i });
      expect(dashboardLink.className).not.toContain('text-teal-700');
    });

    it('handles desktop collapse toggle and switches widths and aria-labels', async () => {
      const onToggle = vi.fn();
      const { unmount } = renderWithProviders(
        <Sidebar collapsed={false} onToggle={onToggle} />
      );

      const collapseBtn = await screen.findByRole('button', { name: /Collapse sidebar/i });
      expect(collapseBtn).toBeInTheDocument();
      fireEvent.click(collapseBtn);
      expect(onToggle).toHaveBeenCalledTimes(1);
      unmount();

      renderWithProviders(
        <Sidebar collapsed={true} onToggle={onToggle} />
      );

      const expandBtn = await screen.findByRole('button', { name: /Expand sidebar/i });
      expect(expandBtn).toBeInTheDocument();
    });

    it('renders mobile drawer and backdrop when mobileOpen is true, and handles click to close', async () => {
      const onCloseMobile = vi.fn();
      const { unmount } = renderWithProviders(
        <Sidebar
          collapsed={false}
          onToggle={vi.fn()}
          mobileOpen={false}
          onCloseMobile={onCloseMobile}
        />
      );

      expect(screen.queryByTestId('mobile-nav-backdrop')).not.toBeInTheDocument();
      unmount();

      renderWithProviders(
        <Sidebar
          collapsed={false}
          onToggle={vi.fn()}
          mobileOpen={true}
          onCloseMobile={onCloseMobile}
        />
      );

      const backdrop = screen.getByTestId('mobile-nav-backdrop');
      expect(backdrop).toBeInTheDocument();
      fireEvent.click(backdrop);
      expect(onCloseMobile).toHaveBeenCalledTimes(1);

      const closeBtn = screen.getByRole('button', { name: /Close navigation menu/i });
      expect(closeBtn).toBeInTheDocument();
      fireEvent.click(closeBtn);
      expect(onCloseMobile).toHaveBeenCalledTimes(2);
    });

    it('invokes onCloseMobile when a navigation link is clicked in mobile mode', async () => {
      const onCloseMobile = vi.fn();
      renderWithProviders(
        <Sidebar
          collapsed={false}
          onToggle={vi.fn()}
          mobileOpen={true}
          onCloseMobile={onCloseMobile}
        />
      );

      const jobsLink = await screen.findByRole('link', { name: /Job Openings/i });
      fireEvent.click(jobsLink);
      expect(onCloseMobile).toHaveBeenCalled();
    });

    it('renders organization brand and user profile footer with sign out button', async () => {
      renderWithProviders(
        <Sidebar collapsed={false} onToggle={vi.fn()} />
      );

      await waitFor(() => {
        expect(screen.getByText('Kartgen Infotech')).toBeInTheDocument();
        expect(screen.getByText('Kartgen HR Admin')).toBeInTheDocument();
        expect(screen.getByText('hr@kartgen.com')).toBeInTheDocument();
      });

      const signOutBtn = screen.getByRole('button', { name: /Sign out/i });
      expect(signOutBtn).toBeInTheDocument();
    });
  });

  describe('Header Topbar Component', () => {
    it('renders dynamic breadcrumbs corresponding to route', async () => {
      renderWithProviders(<Header />, { initialEntries: ['/applications'] });

      await waitFor(() => {
        expect(screen.getByText('Applications')).toBeInTheDocument();
        expect(screen.getByText('Pipeline')).toBeInTheDocument();
      });
    });

    it('renders breadcrumbs for jobs, mailboxes, and docs', async () => {
      const { unmount: unmountJobs } = renderWithProviders(<Header />, { initialEntries: ['/jobs'] });
      await waitFor(() => {
        expect(screen.getByText('Job Openings')).toBeInTheDocument();
        expect(screen.getByText('Requisitions')).toBeInTheDocument();
      });
      unmountJobs();

      const { unmount: unmountMail } = renderWithProviders(<Header />, { initialEntries: ['/mailboxes'] });
      expect(screen.getByText('Mailboxes')).toBeInTheDocument();
      expect(screen.getByText('Integrations')).toBeInTheDocument();
      unmountMail();

      renderWithProviders(<Header />, { initialEntries: ['/docs'] });
      expect(screen.getByText('Documentation')).toBeInTheDocument();
      expect(screen.getByText('Developer Guide')).toBeInTheDocument();
    });

    it('renders mobile hamburger button and invokes onOpenMobileNav', async () => {
      const onOpenMobileNav = vi.fn();
      renderWithProviders(<Header onOpenMobileNav={onOpenMobileNav} />);

      const menuBtn = screen.getByRole('button', { name: /Open navigation menu/i });
      expect(menuBtn).toBeInTheDocument();
      fireEvent.click(menuBtn);
      expect(onOpenMobileNav).toHaveBeenCalledTimes(1);
    });

    it('queries system health and displays Operational badge when UP', async () => {
      vi.mocked(api.getSystemHealth).mockResolvedValueOnce({ status: 'UP' });
      renderWithProviders(<Header />);

      await waitFor(() => {
        expect(api.getSystemHealth).toHaveBeenCalled();
        expect(screen.getByText('Operational')).toBeInTheDocument();
      });

      const healthBtn = screen.getByRole('button', { name: /System health: Operational/i });
      expect(healthBtn).toBeInTheDocument();
      expect(healthBtn.className).toContain('text-emerald-700');
    });

    it('displays Backend Offline badge when system health returns DOWN', async () => {
      vi.mocked(api.getSystemHealth).mockResolvedValueOnce({ status: 'DOWN' });
      renderWithProviders(<Header />);

      await waitFor(() => {
        expect(screen.getByText('Backend Offline')).toBeInTheDocument();
      });

      const healthBtn = screen.getByRole('button', { name: /System health: Backend Offline/i });
      expect(healthBtn).toBeInTheDocument();
      expect(healthBtn.className).toContain('text-rose-700');
    });

    it('allows re-checking system health by clicking status button', async () => {
      vi.mocked(api.getSystemHealth)
        .mockResolvedValueOnce({ status: 'UP' })
        .mockResolvedValueOnce({ status: 'DOWN' });

      renderWithProviders(<Header />);

      await waitFor(() => {
        expect(screen.getByText('Operational')).toBeInTheDocument();
      });

      const healthBtn = screen.getByRole('button', { name: /System health: Operational/i });
      fireEvent.click(healthBtn);

      await waitFor(() => {
        expect(screen.getByText('Backend Offline')).toBeInTheDocument();
      });
    });

    it('renders accessible theme toggle button and toggles between light and dark themes immediately', async () => {
      renderWithProviders(<Header />);

      const themeBtn = screen.getByRole('button', { name: /Switch to dark theme/i });
      expect(themeBtn).toBeInTheDocument();
      expect(document.documentElement.classList.contains('dark')).toBe(false);

      fireEvent.click(themeBtn);

      await waitFor(() => {
        expect(screen.getByRole('button', { name: /Switch to light theme/i })).toBeInTheDocument();
        expect(document.documentElement.classList.contains('dark')).toBe(true);
      });

      fireEvent.click(screen.getByRole('button', { name: /Switch to light theme/i }));

      await waitFor(() => {
        expect(screen.getByRole('button', { name: /Switch to dark theme/i })).toBeInTheDocument();
        expect(document.documentElement.classList.contains('dark')).toBe(false);
      });
    });

    it('renders active organization badge and documentation shortcut', async () => {
      renderWithProviders(<Header />);

      await waitFor(() => {
        expect(screen.getByText('Kartgen Infotech')).toBeInTheDocument();
        expect(screen.getByText('OWNER')).toBeInTheDocument();
      });

      const docsBtn = screen.getByRole('button', { name: /Open Technical Documentation/i });
      expect(docsBtn).toBeInTheDocument();
    });

    it('renders view refresh button when onRefresh is supplied', () => {
      const onRefresh = vi.fn();
      renderWithProviders(<Header onRefresh={onRefresh} isRefreshing={false} />);

      const refreshBtn = screen.getByRole('button', { name: /Refresh current view/i });
      expect(refreshBtn).toBeInTheDocument();
      fireEvent.click(refreshBtn);
      expect(onRefresh).toHaveBeenCalledTimes(1);
    });
  });

  describe('AppLayout Shell Coordination', () => {
    it('renders fluid container max-w-[1536px] and nested child route content', async () => {
      render(
        <MemoryRouter initialEntries={['/test-view']}>
          <ThemeProvider>
            <AuthProvider>
              <Routes>
                <Route element={<AppLayout />}>
                  <Route path="test-view" element={<div data-testid="child-page">Child Content Area</div>} />
                </Route>
              </Routes>
            </AuthProvider>
          </ThemeProvider>
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.getByTestId('child-page')).toBeInTheDocument();
      });

      const mainContainer = screen.getByRole('main');
      expect(mainContainer.className).toContain('max-w-[1536px]');
      expect(mainContainer.className).toContain('w-full');
      expect(mainContainer.className).toContain('mx-auto');
      expect(mainContainer.className).toContain('px-4');
    });

    it('coordinates mobile drawer open via Header hamburger and close via backdrop', async () => {
      render(
        <MemoryRouter initialEntries={['/dashboard']}>
          <ThemeProvider>
            <AuthProvider>
              <Routes>
                <Route element={<AppLayout />}>
                  <Route path="dashboard" element={<div>Dashboard Page</div>} />
                </Route>
              </Routes>
            </AuthProvider>
          </ThemeProvider>
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.getByText('Dashboard Page')).toBeInTheDocument();
      });

      expect(screen.queryByTestId('mobile-nav-backdrop')).not.toBeInTheDocument();

      const hamburgerBtn = screen.getByRole('button', { name: /Open navigation menu/i });
      fireEvent.click(hamburgerBtn);

      const backdrop = await screen.findByTestId('mobile-nav-backdrop');
      expect(backdrop).toBeInTheDocument();

      fireEvent.click(backdrop);

      await waitFor(() => {
        expect(screen.queryByTestId('mobile-nav-backdrop')).not.toBeInTheDocument();
      });
    });

    it('adapts responsive main content margins between collapsed and expanded states', async () => {
      const { container } = render(
        <MemoryRouter initialEntries={['/dashboard']}>
          <ThemeProvider>
            <AuthProvider>
              <Routes>
                <Route element={<AppLayout />}>
                  <Route path="dashboard" element={<div>Dashboard Page</div>} />
                </Route>
              </Routes>
            </AuthProvider>
          </ThemeProvider>
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.getByText('Dashboard Page')).toBeInTheDocument();
      });

      // Initially expanded: contains md:ml-64 and ml-0
      const contentWrapper = screen.getByTestId('main-content-wrapper');
      expect(contentWrapper.className).toContain('md:ml-64');
      expect(contentWrapper.className).toContain('ml-0');

      // Click desktop collapse button
      const collapseBtn = screen.getByRole('button', { name: /Collapse sidebar/i });
      fireEvent.click(collapseBtn);

      await waitFor(() => {
        expect(contentWrapper.className).toContain('md:ml-20');
      });
    });
  });
});
