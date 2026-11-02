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
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';

interface SidebarProps {
  collapsed: boolean;
  onToggle: () => void;
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

export const Sidebar: React.FC<SidebarProps> = ({ collapsed, onToggle }) => {
  const { user, logout } = useAuth();

  return (
    <aside
      className={`fixed top-0 left-0 bottom-0 z-40 bg-slate-900 border-r border-slate-800 transition-all duration-200 flex flex-col ${
        collapsed ? 'w-20' : 'w-64'
      }`}
    >
      {/* Brand Header */}
      <div className="h-16 flex items-center justify-between px-4 border-b border-slate-800 shrink-0">
        <div className="flex items-center gap-3 overflow-hidden">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-emerald-500 to-teal-700 flex items-center justify-center text-white font-bold shadow-md shadow-emerald-950/50 shrink-0">
            <Sparkles className="w-5 h-5 text-white" />
          </div>
          {!collapsed && (
            <div className="flex flex-col min-w-0">
              <span className="font-bold text-white tracking-tight truncate text-base">
                HireFlow <span className="text-emerald-400 font-extrabold text-xs ml-1 px-1.5 py-0.5 rounded bg-emerald-950 border border-emerald-800">AI</span>
              </span>
              <span className="text-[11px] text-slate-400 truncate">
                {user?.organizationName || 'Kartgen Infotech'}
              </span>
            </div>
          )}
        </div>

        <button
          onClick={onToggle}
          className="text-slate-400 hover:text-white p-1.5 rounded-lg hover:bg-slate-800 transition-colors shrink-0"
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
              className={({ isActive }) =>
                `flex items-center gap-3 px-3 py-2.5 rounded-xl text-xs font-medium transition-colors ${
                  isActive
                    ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20'
                    : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
                }`
              }
              title={collapsed ? item.name : undefined}
            >
              <Icon className="w-4 h-4 shrink-0" />
              {!collapsed && <span className="truncate">{item.name}</span>}
            </NavLink>
          );
        })}
      </nav>

      {/* User Footer */}
      <div className="p-3 border-t border-slate-800 bg-slate-950/40 shrink-0">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-lg bg-slate-800 border border-slate-700 flex items-center justify-center text-slate-300 font-semibold text-xs shrink-0">
            {user?.email?.charAt(0).toUpperCase() || 'H'}
          </div>
          {!collapsed && (
            <div className="flex flex-col min-w-0 flex-1">
              <span className="text-xs font-medium text-slate-200 truncate">
                {user?.displayName || 'HR Administrator'}
              </span>
              <span className="text-[10px] text-slate-400 truncate">{user?.email}</span>
            </div>
          )}
          <button
            onClick={logout}
            className="text-slate-400 hover:text-rose-400 p-1.5 rounded-lg hover:bg-slate-800 transition-colors shrink-0"
            title="Sign out"
            aria-label="Sign out"
          >
            <LogOut className="w-4 h-4" />
          </button>
        </div>
      </div>
    </aside>
  );
};
