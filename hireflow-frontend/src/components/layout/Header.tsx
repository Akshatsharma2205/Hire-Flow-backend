import React, { useState, useEffect, useCallback } from 'react';
import {
  Menu,
  ChevronRight,
  Building2,
  BookOpen,
  RefreshCw,
  Sun,
  Moon,
  Search,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { useTheme } from '../../context/ThemeContext';
import { api } from '../../services/api';
import { useNavigate, useLocation, NavLink } from 'react-router-dom';

interface HeaderProps {
  onSearch?: (query: string) => void;
  onRefresh?: () => void;
  isRefreshing?: boolean;
  onOpenMobileNav?: () => void;
}

export const Header: React.FC<HeaderProps> = ({
  onRefresh,
  isRefreshing = false,
  onOpenMobileNav,
}) => {
  const { user } = useAuth();
  const { theme, toggleTheme } = useTheme();
  const navigate = useNavigate();
  const location = useLocation();

  const [healthStatus, setHealthStatus] = useState<'UP' | 'DOWN' | 'CHECKING' | 'UNKNOWN'>('CHECKING');
  const [isCheckingHealth, setIsCheckingHealth] = useState(false);

  const fetchHealth = useCallback(async () => {
    setIsCheckingHealth(true);
    try {
      const res = await api.getSystemHealth();
      setHealthStatus(res.status);
    } catch {
      setHealthStatus('DOWN');
    } finally {
      setIsCheckingHealth(false);
    }
  }, []);

  useEffect(() => {
    fetchHealth();
    const interval = setInterval(fetchHealth, 30000);
    return () => clearInterval(interval);
  }, [fetchHealth]);

  const getBreadcrumbs = (pathname: string) => {
    const clean = pathname.split('?')[0].replace(/\/+$/, '') || '/dashboard';
    switch (clean) {
      case '/dashboard':
      case '/':
        return { section: 'Dashboard', page: 'Overview' };
      case '/candidates':
        return { section: 'Candidates', page: 'Directory' };
      case '/jobs':
        return { section: 'Job Openings', page: 'Requisitions' };
      case '/applications':
        return { section: 'Applications', page: 'Pipeline' };
      case '/mailboxes':
        return { section: 'Mailboxes', page: 'Integrations' };
      case '/rules':
        return { section: 'Recruitment Rules', page: 'Automation' };
      case '/activity':
        return { section: 'Processing Activity', page: 'Audit Log' };
      case '/settings':
        return { section: 'Settings', page: 'Configuration' };
      case '/docs':
        return { section: 'Documentation', page: 'Developer Guide' };
      default: {
        const parts = clean.split('/').filter(Boolean);
        const section = parts[0] ? parts[0].charAt(0).toUpperCase() + parts[0].slice(1) : 'Dashboard';
        const page = parts[1] ? parts[1].charAt(0).toUpperCase() + parts[1].slice(1) : 'Overview';
        return { section, page };
      }
    }
  };

  const breadcrumbs = getBreadcrumbs(location.pathname);

  return (
    <header className="sticky top-0 z-30 flex h-[68px] items-center justify-between gap-4 border-b border-slate-200 bg-[#F7F4EE]/85 px-4 backdrop-blur-xl dark:border-slate-800 dark:bg-[#12100E]/85 sm:px-6">
      {/* Left Section: Mobile Nav Toggle + Breadcrumbs */}
      <div className="flex min-w-0 flex-1 items-center gap-2 sm:gap-3">
        {/* Mobile Hamburger Toggle Button */}
        <button
          type="button"
          onClick={onOpenMobileNav}
          className="-ml-1 shrink-0 rounded-lg p-2 text-slate-600 transition-colors hover:bg-slate-900/5 hover:text-slate-900 focus:outline-none focus-ring dark:text-slate-400 dark:hover:bg-white/5 dark:hover:text-white md:hidden"
          aria-label="Open navigation menu"
        >
          <Menu className="h-5 w-5" />
        </button>

        {/* Dynamic Breadcrumbs */}
        <nav
          aria-label="Breadcrumb"
          className="flex min-w-0 items-center gap-1.5 overflow-hidden text-[12px] font-medium sm:gap-2"
        >
          <NavLink
            to="/dashboard"
            className="shrink-0 text-slate-400 transition-colors hover:text-slate-700 dark:text-slate-500 dark:hover:text-slate-200"
            aria-label="Home"
          >
            HireFlow
          </NavLink>
          <ChevronRight className="h-3 w-3 shrink-0 text-slate-300 dark:text-slate-600" aria-hidden="true" />
          <span className="truncate text-slate-500 dark:text-slate-400">{breadcrumbs.section}</span>
          <ChevronRight className="h-3 w-3 shrink-0 text-slate-300 dark:text-slate-600" aria-hidden="true" />
          <span className="max-w-[140px] truncate font-semibold text-slate-900 dark:text-slate-100 sm:max-w-none">
            {breadcrumbs.page}
          </span>
        </nav>

        {/* Global Quick Search (Desktop) */}
        <div className="mx-4 hidden max-w-xs flex-1 lg:flex">
          <button
            type="button"
            onClick={() => navigate('/candidates')}
            className="flex w-full items-center justify-between rounded-lg border border-slate-200 bg-white/70 px-3 py-1.5 text-[11.5px] text-slate-500 transition-colors hover:border-slate-300 hover:bg-white dark:border-slate-800 dark:bg-slate-900/60 dark:text-slate-400 dark:hover:border-slate-700 dark:hover:bg-slate-900"
          >
            <span className="flex min-w-0 items-center gap-2">
              <Search className="h-3.5 w-3.5 shrink-0 text-slate-400" />
              <span className="truncate">Search candidates, jobs…</span>
            </span>
            <kbd className="hidden shrink-0 rounded border border-slate-200 bg-slate-50 px-1.5 py-0.5 font-mono text-[9px] font-semibold text-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-500 xl:inline-flex">
              ⌘K
            </kbd>
          </button>
        </div>
      </div>

      {/* Right Section: System Health + Theme Toggle + Org + Docs + Refresh */}
      <div className="flex shrink-0 items-center gap-1.5 sm:gap-2">
        {/* Real System Health Indicator */}
        <button
          type="button"
          onClick={fetchHealth}
          className={`flex cursor-pointer items-center gap-2 rounded-full border px-2.5 py-1 text-[10.5px] transition-colors ${
            healthStatus === 'UP'
              ? 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-800/50 dark:bg-emerald-950/40 dark:text-emerald-400'
              : healthStatus === 'DOWN'
              ? 'border-rose-200 bg-rose-50 text-rose-700 dark:border-rose-800/50 dark:bg-rose-950/40 dark:text-rose-400'
              : 'border-slate-200 bg-slate-100 text-slate-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-400'
          }`}
          title="Click to probe system health"
          aria-label={`System health: ${healthStatus === 'UP' ? 'Operational' : healthStatus === 'DOWN' ? 'Backend Offline' : 'Checking...'}`}
        >
          <span className="relative flex h-1.5 w-1.5">
            {healthStatus === 'UP' && (
              <>
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-75" />
                <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-emerald-500" />
              </>
            )}
            {healthStatus === 'DOWN' && <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-rose-500" />}
            {healthStatus !== 'UP' && healthStatus !== 'DOWN' && (
              <span className={`relative inline-flex h-1.5 w-1.5 rounded-full bg-slate-400 ${isCheckingHealth ? 'animate-pulse' : ''}`} />
            )}
          </span>
          <span className="hidden font-semibold sm:inline">
            {healthStatus === 'UP'
              ? 'Operational'
              : healthStatus === 'DOWN'
              ? 'Backend Offline'
              : 'Checking...'}
          </span>
        </button>

        <span className="mx-0.5 hidden h-4 w-px bg-slate-200 dark:bg-slate-800 sm:block" aria-hidden="true" />

        {/* Accessible Theme Toggle Button */}
        <button
          type="button"
          onClick={toggleTheme}
          className="rounded-lg p-2 text-slate-500 transition-colors hover:bg-slate-900/5 hover:text-slate-800 focus:outline-none focus-ring dark:text-slate-400 dark:hover:bg-white/5 dark:hover:text-slate-100"
          title={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
          aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
        >
          {theme === 'dark' ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
        </button>

        {/* Active Organization Badge */}
        <div className="hidden items-center gap-2 rounded-lg border border-slate-200 bg-white/70 px-2.5 py-1 text-[11px] text-slate-700 dark:border-slate-800 dark:bg-slate-900/60 dark:text-slate-300 md:flex">
          <Building2 className="h-3.5 w-3.5 text-slate-400 dark:text-slate-500" />
          <span className="max-w-[130px] truncate font-semibold text-slate-800 dark:text-slate-200">
            {user?.organizationName || 'Kartgen Infotech'}
          </span>
          <span className="rounded border border-teal-200 bg-teal-50 px-1 py-0.5 font-mono text-[9px] font-semibold text-teal-700 dark:border-teal-800/70 dark:bg-teal-950/60 dark:text-teal-300">
            {user?.role || 'OWNER'}
          </span>
        </div>

        {/* Documentation Shortcut */}
        <button
          type="button"
          onClick={() => navigate('/docs')}
          className="rounded-lg p-2 text-slate-500 transition-colors hover:bg-slate-900/5 hover:text-slate-800 focus:outline-none focus-ring dark:text-slate-400 dark:hover:bg-white/5 dark:hover:text-slate-100"
          title="Open Technical Documentation"
          aria-label="Open Technical Documentation"
        >
          <BookOpen className="h-4 w-4" />
        </button>

        {/* Optional View Refresh */}
        {onRefresh && (
          <button
            type="button"
            onClick={onRefresh}
            disabled={isRefreshing}
            className="rounded-lg p-2 text-slate-500 transition-colors hover:bg-slate-900/5 hover:text-slate-800 focus:outline-none focus-ring disabled:opacity-50 dark:text-slate-400 dark:hover:bg-white/5 dark:hover:text-slate-100"
            title="Refresh current view"
            aria-label="Refresh current view"
          >
            <RefreshCw className={`h-4 w-4 ${isRefreshing ? 'animate-spin text-teal-600 dark:text-teal-400' : ''}`} />
          </button>
        )}
      </div>
    </header>
  );
};
