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
  Sparkles,
  X,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';

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
          className="fixed inset-0 bg-slate-900/50 backdrop-blur-sm z-40 md:hidden"
          onClick={onCloseMobile}
          aria-hidden="true"
          data-testid="mobile-nav-backdrop"
        />
      )}

      {/* Sidebar Navigation */}
      <aside
        className={`fixed inset-y-0 left-0 z-50 md:z-30 bg-white border-r border-slate-200 text-slate-900 dark:bg-slate-900 dark:border-slate-800 dark:text-slate-100 flex flex-col transition-all duration-200 ease-in-out ${
          mobileOpen ? 'translate-x-0' : '-translate-x-full md:translate-x-0'
        } ${collapsed ? 'w-64 md:w-20' : 'w-64'}`}
        aria-label="Sidebar Navigation"
      >
        {/* Brand Header */}
        <div className="h-16 flex items-center justify-between px-4 border-b border-slate-200 dark:border-slate-800 shrink-0">
          <div className="flex items-center gap-3 overflow-hidden">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-teal-600 to-teal-800 flex items-center justify-center text-white font-bold shadow-sm shrink-0">
              <Sparkles className="w-5 h-5 text-white" />
            </div>
            <div className={`flex flex-col min-w-0 ${collapsed ? 'md:hidden' : ''}`}>
              <div className="flex items-center gap-1.5">
                <span className="font-bold text-slate-900 dark:text-white tracking-tight truncate text-base">
                  HireFlow
                </span>
                <span className="text-[10px] font-bold text-teal-700 bg-teal-50 border border-teal-200 dark:text-teal-400 dark:bg-teal-950/60 dark:border-teal-800 px-1.5 py-0.5 rounded">
                  AI
                </span>
              </div>
              <span className="text-[11px] text-slate-500 dark:text-slate-400 truncate">
                {user?.organizationName || 'Kartgen Infotech'}
              </span>
            </div>
          </div>

          {/* Mobile Close Button */}
          <button
            onClick={onCloseMobile}
            className="md:hidden text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-white p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors shrink-0"
            aria-label="Close navigation menu"
          >
            <X className="w-5 h-5" />
          </button>

          {/* Desktop Collapse Toggle */}
          <button
            onClick={onToggle}
            className="hidden md:flex text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-white p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors shrink-0"
            title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          >
            {collapsed ? <ChevronRight className="w-4 h-4" /> : <ChevronLeft className="w-4 h-4" />}
          </button>
        </div>

        {/* Navigation Links */}
        <nav className="flex-1 overflow-y-auto px-3 py-4 space-y-1">
          {navItems.map((item) => {
            const Icon = item.icon;
            return (
              <NavLink
                key={item.path}
                to={item.path}
                onClick={() => onCloseMobile?.()}
                className={({ isActive }) =>
                  `flex items-center gap-3 px-3 py-2.5 rounded-xl text-xs transition-colors ${
                    isActive
                      ? 'bg-teal-50 text-teal-700 border border-teal-200 font-medium dark:bg-teal-950/40 dark:text-teal-400 dark:border-teal-800/40'
                      : 'text-slate-600 hover:text-slate-900 hover:bg-slate-100/80 dark:text-slate-400 dark:hover:text-slate-200 dark:hover:bg-slate-800/60'
                  }`
                }
                title={collapsed ? item.name : undefined}
              >
                <Icon className="w-4 h-4 shrink-0" />
                <span className={`truncate ${collapsed ? 'md:hidden' : ''}`}>{item.name}</span>
              </NavLink>
            );
          })}
        </nav>

        {/* User Footer */}
        <div className="p-3 border-t border-slate-200 dark:border-slate-800 bg-slate-50/70 dark:bg-slate-950/40 shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-lg bg-teal-100 dark:bg-slate-800 border border-teal-200 dark:border-slate-700 flex items-center justify-center text-teal-800 dark:text-slate-200 font-semibold text-xs shrink-0">
              {user?.email?.charAt(0).toUpperCase() || 'H'}
            </div>
            <div className={`flex flex-col min-w-0 flex-1 ${collapsed ? 'md:hidden' : ''}`}>
              <span className="text-xs font-medium text-slate-900 dark:text-slate-200 truncate">
                {user?.displayName || 'HR Administrator'}
              </span>
              <span className="text-[11px] text-slate-500 dark:text-slate-400 truncate">{user?.email}</span>
            </div>
            <button
              onClick={logout}
              className="text-slate-400 hover:text-rose-600 dark:hover:text-rose-400 p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors shrink-0"
              title="Sign out"
              aria-label="Sign out"
            >
              <LogOut className="w-4 h-4" />
            </button>
          </div>
        </div>
      </aside>
    </>
  );
};
