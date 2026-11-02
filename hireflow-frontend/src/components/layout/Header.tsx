import React, { useState, useEffect, useCallback } from 'react';
import {
  Menu,
  Home,
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
    <header className="h-16 bg-white/80 dark:bg-slate-900/80 backdrop-blur-md border-b border-slate-200 dark:border-slate-800 px-4 sm:px-6 flex items-center justify-between sticky top-0 z-30">
      {/* Left Section: Mobile Nav Toggle + Breadcrumbs */}
      <div className="flex items-center gap-2 sm:gap-3 flex-1 min-w-0 mr-2 sm:mr-4">
        {/* Mobile Hamburger Toggle Button */}
        <button
          type="button"
          onClick={onOpenMobileNav}
          className="md:hidden p-2 -ml-2 text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors focus:outline-none focus-ring shrink-0"
          aria-label="Open navigation menu"
        >
          <Menu className="w-5 h-5" />
        </button>

        {/* Dynamic Breadcrumbs */}
        <nav aria-label="Breadcrumb" className="flex items-center gap-1.5 sm:gap-2 text-xs font-medium overflow-hidden">
          <NavLink
            to="/dashboard"
            className="flex items-center gap-1.5 text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200 transition-colors shrink-0"
            aria-label="Home"
          >
            <Home className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">HireFlow</span>
          </NavLink>
          <ChevronRight className="w-3.5 h-3.5 text-slate-400 dark:text-slate-600 shrink-0" aria-hidden="true" />
          <span className="text-slate-500 dark:text-slate-400 truncate">
            {breadcrumbs.section}
          </span>
          <ChevronRight className="w-3.5 h-3.5 text-slate-400 dark:text-slate-600 shrink-0" aria-hidden="true" />
          <span className="text-slate-900 dark:text-slate-100 font-semibold truncate max-w-[140px] sm:max-w-none">
            {breadcrumbs.page}
          </span>
        </nav>

        {/* Global Quick Search (Desktop) */}
        <div className="hidden lg:flex items-center mx-4 flex-1 max-w-xs">
          <div
            onClick={() => navigate('/candidates')}
            className="w-full flex items-center justify-between px-3 py-1.5 rounded-xl bg-slate-100/80 hover:bg-slate-200/60 dark:bg-slate-800/60 dark:hover:bg-slate-800 border border-slate-200/80 dark:border-slate-700/80 text-xs text-slate-500 dark:text-slate-400 cursor-pointer transition-colors shadow-xs"
          >
            <span className="flex items-center gap-2 truncate">
              <Search className="w-3.5 h-3.5 text-slate-400" />
              <span>Search candidates, jobs...</span>
            </span>
            <kbd className="hidden xl:inline-flex items-center gap-0.5 px-1.5 py-0.5 text-[10px] font-mono font-semibold text-slate-500 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded shadow-xs">
              ⌘K
            </kbd>
          </div>
        </div>
      </div>

      {/* Right Section: System Health + Theme Toggle + Org + Docs + Refresh */}
      <div className="flex items-center gap-2 sm:gap-3 shrink-0">
        {/* Real System Health Indicator */}
        <button
          type="button"
          onClick={fetchHealth}
          className={`flex items-center gap-2 px-2.5 py-1 rounded-full text-xs transition-colors border cursor-pointer ${
            healthStatus === 'UP'
              ? 'bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-950/40 dark:text-emerald-400 dark:border-emerald-800/40'
              : healthStatus === 'DOWN'
              ? 'bg-rose-50 text-rose-700 border-rose-200 dark:bg-rose-950/40 dark:text-rose-400 dark:border-rose-800/40'
              : 'bg-slate-100 text-slate-600 border-slate-200 dark:bg-slate-800 dark:text-slate-400 dark:border-slate-700'
          }`}
          title="Click to probe system health"
          aria-label={`System health: ${healthStatus === 'UP' ? 'Operational' : healthStatus === 'DOWN' ? 'Backend Offline' : 'Checking...'}`}
        >
          <span className="relative flex h-2 w-2">
            {healthStatus === 'UP' && (
              <>
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75" />
                <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-500" />
              </>
            )}
            {healthStatus === 'DOWN' && (
              <span className="relative inline-flex rounded-full h-2 w-2 bg-rose-500" />
            )}
            {healthStatus !== 'UP' && healthStatus !== 'DOWN' && (
              <span className={`relative inline-flex rounded-full h-2 w-2 bg-slate-400 ${isCheckingHealth ? 'animate-pulse' : ''}`} />
            )}
          </span>
          <span className="font-medium text-[11px]">
            {healthStatus === 'UP'
              ? 'Operational'
              : healthStatus === 'DOWN'
              ? 'Backend Offline'
              : 'Checking...'}
          </span>
        </button>

        {/* Accessible Theme Toggle Button */}
        <button
          type="button"
          onClick={toggleTheme}
          className="p-2 text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-100 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors focus:outline-none focus-ring"
          title={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
          aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
        >
          {theme === 'dark' ? (
            <Sun className="w-4 h-4 text-amber-400" />
          ) : (
            <Moon className="w-4 h-4 text-slate-600" />
          )}
        </button>

        {/* Active Organization Badge */}
        <div className="hidden md:flex items-center gap-2 px-3 py-1 rounded-lg bg-slate-100 dark:bg-slate-800/90 border border-slate-200 dark:border-slate-700 text-xs text-slate-700 dark:text-slate-300">
          <Building2 className="w-3.5 h-3.5 text-slate-500 dark:text-slate-400" />
          <span className="font-semibold text-slate-800 dark:text-slate-200 truncate max-w-[130px]">
            {user?.organizationName || 'Kartgen Infotech'}
          </span>
          <span className="text-[10px] font-bold text-teal-700 bg-teal-50 border border-teal-200 dark:text-teal-400 dark:bg-teal-950/60 dark:border-teal-800 px-1.5 py-0.5 rounded font-mono">
            {user?.role || 'OWNER'}
          </span>
        </div>

        {/* Documentation Shortcut */}
        <button
          type="button"
          onClick={() => navigate('/docs')}
          className="p-2 text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-100 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors focus:outline-none focus-ring"
          title="Open Technical Documentation"
          aria-label="Open Technical Documentation"
        >
          <BookOpen className="w-4 h-4" />
        </button>

        {/* Optional View Refresh */}
        {onRefresh && (
          <button
            type="button"
            onClick={onRefresh}
            disabled={isRefreshing}
            className="p-2 text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-100 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors disabled:opacity-50 focus:outline-none focus-ring"
            title="Refresh current view"
            aria-label="Refresh current view"
          >
            <RefreshCw className={`w-4 h-4 ${isRefreshing ? 'animate-spin text-teal-600 dark:text-teal-400' : ''}`} />
          </button>
        )}
      </div>
    </header>
  );
};

