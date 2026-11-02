import React from 'react';
import { NavLink } from 'react-router-dom';
import {
  LayoutDashboard,
  Users,
  Briefcase,
  Kanban,
  Mail,
  Sliders,
  Activity,
  Settings,
  BookOpen,
  LogOut,
  ChevronLeft,
  ChevronRight,
  X,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { BrandMark } from '../brand/BrandMark';

interface SidebarProps {
  collapsed: boolean;
  onToggle: () => void;
  mobileOpen?: boolean;
  onCloseMobile?: () => void;
}

const navItems = [
  { name: 'Dashboard', path: '/dashboard', icon: LayoutDashboard },
  { name: 'Candidates', path: '/candidates', icon: Users },
  { name: 'Job Openings', path: '/jobs', icon: Briefcase },
  { name: 'Applications', path: '/applications', icon: Kanban },
  { name: 'Mailboxes', path: '/mailboxes', icon: Mail },
  { name: 'Recruitment Rules', path: '/rules', icon: Sliders },
  { name: 'Processing Activity', path: '/activity', icon: Activity },
  { name: 'Settings', path: '/settings', icon: Settings },
  { name: 'Documentation', path: '/docs', icon: BookOpen },
];

export const Sidebar: React.FC<SidebarProps> = ({
  collapsed,
  onToggle,
  mobileOpen = false,
  onCloseMobile,
}) => {
  const { user, logout } = useAuth();

  return (
    <>
      {/* Mobile Drawer Backdrop Overlay */}
      {mobileOpen && (
        <div
          className="fixed inset-0 z-40 bg-slate-950/45 backdrop-blur-sm md:hidden"
          onClick={onCloseMobile}
          aria-hidden="true"
          data-testid="mobile-nav-backdrop"
        />
      )}

      {/* Sidebar Navigation */}
      <aside
        className={`fixed inset-y-0 left-0 z-50 flex flex-col border-r border-slate-200 bg-white text-slate-900 transition-[width,transform] duration-300 ease-out-expo dark:border-slate-800 dark:bg-slate-900 dark:text-slate-100 md:z-30 ${
          mobileOpen ? 'translate-x-0' : '-translate-x-full md:translate-x-0'
        } ${collapsed ? 'w-64 md:w-20' : 'w-64'}`}
        aria-label="Sidebar Navigation"
      >
        {/* Brand Header */}
        <div className="flex h-[68px] shrink-0 items-center justify-between border-b border-slate-200 px-4 dark:border-slate-800">
          <NavLink
            to="/dashboard"
            className="flex min-w-0 items-center gap-3 overflow-hidden"
            aria-label="HireFlow home"
          >
            <BrandMark size="md" className="shrink-0" />
            <div className={`flex min-w-0 flex-col ${collapsed ? 'md:hidden' : ''}`}>
              <span className="truncate text-[15px] font-semibold leading-none tracking-[-0.035em] text-slate-900 dark:text-slate-50">
                HireFlow
              </span>
              <span className="mt-1 truncate text-[10.5px] leading-none text-slate-500 dark:text-slate-400">
                {user?.organizationName || 'Kartgen Infotech'}
              </span>
            </div>
          </NavLink>

          {/* Mobile Close Button */}
          <button
            onClick={onCloseMobile}
            className="shrink-0 rounded-lg p-1.5 text-slate-500 transition-colors hover:bg-slate-100 hover:text-slate-900 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-white md:hidden"
            aria-label="Close navigation menu"
          >
            <X className="h-5 w-5" />
          </button>

          {/* Desktop Collapse Toggle */}
          <button
            onClick={onToggle}
            className={`shrink-0 rounded-lg p-1.5 text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-800 dark:text-slate-500 dark:hover:bg-slate-800 dark:hover:text-slate-100 ${
              collapsed ? 'hidden md:absolute md:-right-3 md:top-[76px] md:z-10 md:border md:border-slate-200 md:bg-white md:shadow-sm dark:md:border-slate-700 dark:md:bg-slate-900' : 'hidden md:flex'
            }`}
            title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          >
            {collapsed ? <ChevronRight className="h-4 w-4" /> : <ChevronLeft className="h-4 w-4" />}
          </button>
        </div>

        {/* Navigation Links */}
        <nav className="scrollbar-none flex-1 space-y-0.5 overflow-y-auto px-2.5 py-4">
          <p
            className={`px-3 pb-2 text-[10px] font-semibold uppercase tracking-[0.16em] text-slate-400 dark:text-slate-500 ${
              collapsed ? 'md:hidden' : ''
            }`}
          >
            Workspace
          </p>
          {navItems.map((item) => {
            const Icon = item.icon;
            return (
              <NavLink
                key={item.path}
                to={item.path}
                onClick={() => onCloseMobile?.()}
                className={({ isActive }) =>
                  `group relative flex items-center gap-3 rounded-lg px-3 py-2.5 text-[12.5px] transition-colors duration-150 ${
                    isActive
                      ? 'bg-teal-50 font-semibold text-teal-700 dark:bg-teal-950/50 dark:text-teal-300'
                      : 'font-medium text-slate-600 hover:bg-slate-100/80 hover:text-slate-900 dark:text-slate-400 dark:hover:bg-slate-800/60 dark:hover:text-slate-100'
                  } ${collapsed ? 'md:justify-center md:px-0' : ''}`
                }
                title={collapsed ? item.name : undefined}
              >
                {({ isActive }) => (
                  <>
                    {isActive && (
                      <span className="absolute left-0 top-1/2 hidden h-4 w-[2px] -translate-y-1/2 rounded-full bg-teal-700 dark:bg-teal-400 md:block" />
                    )}
                    <Icon
                      className={`h-[17px] w-[17px] shrink-0 transition-colors ${
                        isActive ? 'text-teal-700 dark:text-teal-300' : 'text-slate-400 group-hover:text-slate-600 dark:text-slate-500 dark:group-hover:text-slate-300'
                      }`}
                    />
                    <span className={`truncate ${collapsed ? 'md:hidden' : ''}`}>{item.name}</span>
                  </>
                )}
              </NavLink>
            );
          })}
        </nav>

        {/* User Footer */}
        <div className="shrink-0 border-t border-slate-200 p-3 dark:border-slate-800">
          <div
            className={`flex items-center gap-2.5 rounded-xl border border-slate-200 bg-slate-50/80 p-2 dark:border-slate-800 dark:bg-slate-950/50 ${
              collapsed ? 'md:justify-center md:border-0 md:bg-transparent md:p-0 dark:md:bg-transparent' : ''
            }`}
          >
            <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-teal-700 text-[11px] font-semibold text-paper-50">
              {user?.email?.charAt(0).toUpperCase() || 'H'}
            </div>
            <div className={`flex min-w-0 flex-1 flex-col ${collapsed ? 'md:hidden' : ''}`}>
              <span className="truncate text-[12px] font-semibold leading-tight text-slate-900 dark:text-slate-100">
                {user?.displayName || 'HR Administrator'}
              </span>
              <span className="mt-0.5 truncate text-[10.5px] leading-tight text-slate-500 dark:text-slate-400">
                {user?.email}
              </span>
            </div>
            <button
              onClick={logout}
              className={`shrink-0 rounded-md p-1.5 text-slate-400 transition-colors hover:bg-white hover:text-rose-600 dark:hover:bg-slate-800 dark:hover:text-rose-400 ${
                collapsed ? 'md:hidden' : ''
              }`}
              title="Sign out"
              aria-label="Sign out"
            >
              <LogOut className="h-4 w-4" />
            </button>
          </div>
        </div>
      </aside>
    </>
  );
};
